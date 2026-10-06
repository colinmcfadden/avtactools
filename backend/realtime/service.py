"""The mission-pack live service: tells everyone with a pack open what just changed, and who else is there.

The API numbers and stores every change (routes/pack_routes.py) and, in the same
transaction, sends a Postgres NOTIFY on ``mission_pack_events``. This service
LISTENs and passes each one to the sockets that have that pack open. It holds no
pack data and decides nothing about access itself: a socket's first message
carries the person's token, and the service asks the API whether that token may
see the pack (``GET /api/packs/<uuid>/access``), again whenever the pack's
membership changes, and every few minutes. Edits never come through here; they
go to the API, which is why one gunicorn worker is enough for it.

It runs in its own container (a socket per open pack would tie up the API's
threads), beside the API, like the LiDAR build service. See docs/MISSION_PACKS.md,
"The live stream", for the protocol a client speaks.

Settings, by environment variable:

    DATABASE_URL              the API's Postgres (the Session pooler: LISTEN needs a session of its own)
    REALTIME_API_URL          the API's root, e.g. http://backend:5000 (without /api)
    REALTIME_PORT             default 8091
    REALTIME_ALLOWED_ORIGINS  comma-separated browser origins; unset allows any. A socket proves
                              itself with a token, never a cookie, so this is defence in depth.

    python service.py
"""

import asyncio
import json
import logging
import os
import secrets
import select
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http import HTTPStatus

from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed

CHANNEL = 'mission_pack_events'
HELLO_SECONDS = 10
RECHECK_SECONDS = 300
PRESENCE_SECONDS = 0.1
MAX_FRAME = 8192
MAX_FOCUS = 1024
MAX_QUEUE = 1000
# Events after which someone may have lost (or gained) the right to see a pack.
MEMBERSHIP_EVENTS = frozenset({'member.add', 'member.join', 'member.role', 'member.remove', 'pack.share', 'pack.transfer'})

# Close codes a client acts on: 4401 sign in again; 4403 and 4404 the pack is not yours to see; 4410 it was
# deleted; 4400 and 4408 a client bug; 1013 the service is busy or cannot reach the API, so try again later.
CLOSE_CODES = {
    'bad_hello': 4400, 'unauthorized': 4401, 'forbidden': 4403, 'not_in_pack': 4404,
    'hello_timeout': 4408, 'pack_gone': 4410, 'unavailable': 1013, 'too_slow': 1013,
}

log = logging.getLogger('realtime')


class Refused(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


class ApiAccess:
    """Asks the API whether a token may see a pack. The API is the only judge of that."""

    def __init__(self, base_url, timeout=10):
        self.base_url = base_url.rstrip('/')
        self.timeout = timeout

    async def check(self, token, pack_uuid):
        return await asyncio.to_thread(self._check, token, pack_uuid)

    def _check(self, token, pack_uuid):
        url = f'{self.base_url}/api/packs/{urllib.parse.quote(pack_uuid, safe="")}/access'
        request = urllib.request.Request(url, headers={'Authorization': f'Bearer {token}', 'Accept': 'application/json'})
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return json.loads(response.read())
        except urllib.error.HTTPError as error:
            # 422 is the JWT library's answer to a token it cannot read.
            code = {401: 'unauthorized', 422: 'unauthorized', 403: 'forbidden', 404: 'not_in_pack'}.get(error.code)
            raise Refused(code or 'unavailable') from None
        except (urllib.error.URLError, OSError, ValueError):
            raise Refused('unavailable') from None


class Member:
    """One open socket: one person, one pack."""

    def __init__(self, ws):
        self.ws = ws
        self.session = secrets.token_hex(8)
        self.pack = None
        self.token = None
        self.user = None
        self.focus = None
        self.ready = False
        self.queue = asyncio.Queue()
        # What was announced while the API was still being asked about this socket: sent only once it says yes.
        self.held = []

    def put(self, message):
        """Queue a message, in order. A socket that falls this far behind is closed: it catches up from the API."""
        if not self.ready:
            if len(self.held) >= MAX_QUEUE:
                return False
            self.held.append(message)
            return True
        if self.queue.qsize() >= MAX_QUEUE:
            return False
        self.queue.put_nowait(message)
        return True


def _text(message):
    return json.dumps(message, separators=(',', ':'))


class Hub:
    """The open packs and who has them open."""

    def __init__(self, access):
        self.access = access
        self.rooms = {}
        self.dirty = set()
        self.rechecking = {}
        self.recheck_again = set()

    # -- sockets --------------------------------------------------------------

    async def serve_socket(self, ws):
        member = Member(ws)
        writer = asyncio.create_task(self._write(member))
        try:
            await self._open(member)
            if member.ready:
                async for raw in ws:
                    self._receive(member, raw)
        except ConnectionClosed:
            pass
        finally:
            self._leave(member)
            member.queue.put_nowait(None)
            await writer

    async def _open(self, member):
        try:
            raw = await asyncio.wait_for(member.ws.recv(), HELLO_SECONDS)
        except asyncio.TimeoutError:
            return self._shut(member, 'hello_timeout')
        hello = _json(raw)
        if not isinstance(hello, dict) or hello.get('type') != 'hello':
            return self._shut(member, 'bad_hello')
        pack, token = hello.get('pack'), hello.get('token')
        if not isinstance(pack, str) or not 0 < len(pack) <= 36 or not isinstance(token, str) or not 0 < len(token) <= 4096:
            return self._shut(member, 'bad_hello')
        member.pack, member.token = pack, token
        # In the room before the check, so nothing committed meanwhile is missed: what arrives is held
        # until the check passes, and a client drops anything it already has by its number.
        self.rooms.setdefault(pack, set()).add(member)
        try:
            granted = await self.access.check(token, pack)
            member.user = {'id': granted['user']['id'], 'name': granted['user']['name']}
            welcome = {'type': 'welcome', 'pack': pack, 'head_seq': granted['head_seq'], 'role': granted['role'],
                       'status': granted['status'],
                       'you': {'session': member.session, 'user_id': member.user['id'], 'name': member.user['name']}}
        except Refused as refused:
            return self._shut(member, refused.code)
        except (KeyError, TypeError):
            return self._shut(member, 'unavailable')
        if member not in self.rooms.get(pack, ()):
            return None                                 # shut while the API was asked (the pack was deleted)
        held, member.held = member.held, []
        member.ready = True
        member.put(welcome)
        for message in held:
            member.put(message)
        self.dirty.add(pack)

    def _receive(self, member, raw):
        message = _json(raw)
        if not isinstance(message, dict):
            return
        if message.get('type') == 'token' and isinstance(message.get('token'), str) and 0 < len(message['token']) <= 4096:
            member.token = message['token']                     # a refreshed token, used from the next check
        elif message.get('type') == 'presence':
            focus = message.get('focus')
            if focus is None or (isinstance(focus, dict) and len(_text(focus)) <= MAX_FOCUS):
                member.focus = focus
                self.dirty.add(member.pack)

    def _leave(self, member):
        room = self.rooms.get(member.pack)
        if room is not None:
            room.discard(member)
            if not room:
                del self.rooms[member.pack]
            self.dirty.add(member.pack)

    def _shut(self, member, code):
        """Say why, then close. The close frame goes after everything already queued."""
        member.ready = False
        member.held = []
        member.queue.put_nowait({'type': 'closed', 'reason': code})
        member.queue.put_nowait(('close', CLOSE_CODES.get(code, 1011), code))
        self._leave(member)

    async def _write(self, member):
        while True:
            message = await member.queue.get()
            if message is None:
                return
            try:
                if isinstance(message, tuple):
                    await member.ws.close(message[1], message[2])
                    return
                await member.ws.send(_text(message))
            except ConnectionClosed:
                return

    # -- what the API announces ----------------------------------------------------

    def deliver_text(self, payload):
        """One NOTIFY from the API."""
        announced = _json(payload)
        if not isinstance(announced, dict) or not isinstance(announced.get('pack'), str):
            return
        pack = announced['pack']
        room = self.rooms.get(pack)
        if not room:
            return
        if announced.get('deleted'):
            for member in list(room):
                self._shut(member, 'pack_gone')
            return
        seq = announced.get('seq')
        if not isinstance(seq, int):
            return
        event = announced.get('event')
        message = ({'type': 'event', 'pack': pack, 'seq': seq, 'event': event} if isinstance(event, dict)
                   else {'type': 'head', 'pack': pack, 'seq': seq})
        for member in list(room):
            if not member.put(message):
                self._shut(member, 'too_slow')
        if isinstance(event, dict) and event.get('type') in MEMBERSHIP_EVENTS:
            self.schedule_recheck(pack)

    def resync_all(self):
        """The LISTEN connection dropped and came back: whatever was announced meanwhile is lost, so everyone catches up."""
        for room in self.rooms.values():
            for member in list(room):
                if member.ready:
                    member.put({'type': 'resync', 'pack': member.pack})

    # -- access, again --------------------------------------------------------------

    def schedule_recheck(self, pack):
        """Ask the API about everyone in a pack again. Asked while a check runs, it runs once more after."""
        if pack in self.rechecking:
            self.recheck_again.add(pack)
            return
        self.rechecking[pack] = asyncio.get_running_loop().create_task(self._recheck(pack))

    async def _recheck(self, pack):
        try:
            while True:
                self.recheck_again.discard(pack)
                members = [m for m in self.rooms.get(pack, ()) if m.ready]
                results = await asyncio.gather(*(self._still_allowed(m) for m in members))
                for member, refusal in zip(members, results):
                    if refusal and member.ready:
                        self._shut(member, refusal)
                if pack not in self.recheck_again:
                    return
        finally:
            self.rechecking.pop(pack, None)

    async def _still_allowed(self, member):
        try:
            await self.access.check(member.token, member.pack)
            return None
        except Refused as refused:
            # A service that cannot reach the API keeps its sockets: it would rather relay than drop everyone.
            return None if refused.code == 'unavailable' else refused.code

    async def recheck_forever(self):
        while True:
            await asyncio.sleep(RECHECK_SECONDS)
            for pack in list(self.rooms):
                self.schedule_recheck(pack)

    # -- presence ---------------------------------------------------------------------

    def presence(self, pack):
        return {'type': 'presence', 'pack': pack, 'people': [
            {'session': m.session, 'user_id': m.user['id'], 'name': m.user['name'], 'focus': m.focus}
            for m in sorted(self.rooms.get(pack, ()), key=lambda m: m.session) if m.ready
        ]}

    def flush_presence(self):
        dirty, self.dirty = self.dirty, set()
        for pack in dirty:
            message = self.presence(pack)
            for member in list(self.rooms.get(pack, ())):
                if member.ready and not member.put(message):
                    self._shut(member, 'too_slow')

    async def presence_forever(self):
        while True:
            await asyncio.sleep(PRESENCE_SECONDS)
            self.flush_presence()


def _json(raw):
    if not isinstance(raw, str):
        return None
    try:
        return json.loads(raw)
    except ValueError:
        return None


# -- LISTEN -------------------------------------------------------------------------

def dsn_from(database_url):
    """DATABASE_URL as libpq wants it: the SQLAlchemy driver name taken out."""
    url = database_url.strip()
    for prefix in ('postgresql+psycopg2://', 'postgres+psycopg2://'):
        if url.startswith(prefix):
            return 'postgresql://' + url[len(prefix):]
    return url


class Listener:
    """LISTENs on its own connection, in a thread, and hands each NOTIFY to the event loop.

    A dropped connection is opened again with growing pauses; announcements made
    while it was down are lost, so every socket is told to catch up from the API.
    """

    IDLE_SECONDS = 60

    def __init__(self, dsn, on_notify, on_reconnect):
        self.dsn = dsn
        self.on_notify = on_notify
        self.on_reconnect = on_reconnect
        self.stopped = threading.Event()

    def run(self):
        import psycopg2  # only the service needs it, not code that imports this module for its logic

        connected_before = False
        pause = 1
        while not self.stopped.is_set():
            try:
                connection = psycopg2.connect(self.dsn)
                connection.set_session(autocommit=True)
                with connection.cursor() as cursor:
                    cursor.execute(f'LISTEN {CHANNEL}')
                log.info('listening for pack changes')
                if connected_before:
                    self.on_reconnect()
                connected_before, pause = True, 1
                self._drain(connection, psycopg2)
            except psycopg2.Error:
                log.warning('the LISTEN connection failed; trying again in %s s', pause, exc_info=True)
            except OSError:
                log.warning('the LISTEN connection failed; trying again in %s s', pause, exc_info=True)
            self.stopped.wait(pause)
            pause = min(pause * 2, 30)

    def _drain(self, connection, psycopg2):
        idle_since = time.monotonic()
        try:
            while not self.stopped.is_set():
                readable, _, _ = select.select([connection], [], [], 5)
                if not readable:
                    if time.monotonic() - idle_since > self.IDLE_SECONDS:
                        # A connection a pooler or a network dropped without a word fails here, not never.
                        with connection.cursor() as cursor:
                            cursor.execute('SELECT 1')
                        idle_since = time.monotonic()
                    continue
                connection.poll()
                idle_since = time.monotonic()
                while connection.notifies:
                    self.on_notify(connection.notifies.pop(0).payload)
        finally:
            try:
                connection.close()
            except psycopg2.Error:
                pass


# -- The server -------------------------------------------------------------------------

def health(connection, request):
    """Answer a health check over plain HTTP; open a socket only on / or /live."""
    if request.path == '/health':
        return connection.respond(HTTPStatus.OK, 'ok\n')
    if request.path.split('?')[0] not in ('/', '/live'):
        return connection.respond(HTTPStatus.NOT_FOUND, 'not found\n')
    return None


async def run(environ, ready=None):
    api_url = (environ.get('REALTIME_API_URL') or '').strip()
    database_url = (environ.get('DATABASE_URL') or '').strip()
    if not api_url or not database_url.startswith(('postgres://', 'postgresql')):
        raise SystemExit('REALTIME_API_URL and a Postgres DATABASE_URL are required')
    port = int(environ.get('REALTIME_PORT') or 8091)
    origins = [o.strip() for o in (environ.get('REALTIME_ALLOWED_ORIGINS') or '').split(',') if o.strip()]

    hub = Hub(ApiAccess(api_url))
    loop = asyncio.get_running_loop()
    listener = Listener(
        dsn_from(database_url),
        on_notify=lambda payload: loop.call_soon_threadsafe(hub.deliver_text, payload),
        on_reconnect=lambda: loop.call_soon_threadsafe(hub.resync_all),
    )
    threading.Thread(target=listener.run, name='listen', daemon=True).start()
    background = [asyncio.create_task(hub.presence_forever()), asyncio.create_task(hub.recheck_forever())]
    async with serve(hub.serve_socket, '0.0.0.0', port, process_request=health, max_size=MAX_FRAME,
                     # Under Cloudflare's 100 s idle timeout, so a quiet socket stays open.
                     ping_interval=30, ping_timeout=30,
                     origins=(origins + [None]) if origins else None) as server:
        log.info('live service on port %s', port)
        if ready is not None:
            ready(server)
        try:
            await server.serve_forever()
        finally:
            listener.stopped.set()
            for task in background:
                task.cancel()


def main():
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(name)s: %(message)s')
    asyncio.run(run(os.environ))


if __name__ == '__main__':
    main()
