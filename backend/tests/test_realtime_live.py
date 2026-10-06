"""The whole live chain against a real Postgres: an edit through the API reaches another member's socket.

The real pack routes (tests/live_server.py) over Postgres, their NOTIFY in the
transaction, the live service LISTENing and asking the API about each socket, and
two people's sockets. What SQLite and the in-process tests cannot show: that
pg_notify is sent and arrives, in order, only once committed, and that the
service's access checks against the real API close the right sockets.

Runs when ``EZPZ_LIVE_POSTGRES`` names a Postgres server on this machine, e.g.

    docker run -d --rm --name ezpz-live-pg -e POSTGRES_PASSWORD=live -p 127.0.0.1:55432:5432 postgres:16-alpine
    $env:EZPZ_LIVE_POSTGRES="postgresql://postgres:live@127.0.0.1:55432/postgres"
    python -m pytest tests/test_realtime_live.py

It makes a database of its own there and drops it after. It needs websockets and
psycopg2 (backend/realtime/requirements.txt) in the Python that runs it.
"""

import asyncio
import json
import os
import socket
import subprocess
import sys
import time
import unittest
import uuid
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

BACKEND_DIR = Path(__file__).resolve().parents[1]
SERVER = os.environ.get('EZPZ_LIVE_POSTGRES', '').strip()

if not SERVER:
    raise unittest.SkipTest('EZPZ_LIVE_POSTGRES is not set')
try:
    import psycopg2
    import requests
    from websockets.asyncio.client import connect
    from websockets.exceptions import ConnectionClosed
except ImportError as missing:  # pragma: no cover - depends on the environment
    raise unittest.SkipTest(f'{missing.name} is not installed')

if urlsplit(SERVER).hostname not in ('127.0.0.1', 'localhost', '::1'):
    raise RuntimeError('EZPZ_LIVE_POSTGRES must be a server on this machine: the test makes and drops a database there')

PASSWORD = 'a secure flight password'


def free_port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


class LiveChainTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.database = f'ezpz_live_{uuid.uuid4().hex[:12]}'
        admin = psycopg2.connect(SERVER)
        admin.autocommit = True
        with admin.cursor() as cursor:
            cursor.execute(f'CREATE DATABASE {cls.database}')
        admin.close()
        parts = urlsplit(SERVER)
        database_url = urlunsplit((parts.scheme, parts.netloc, f'/{cls.database}', '', ''))

        live_port = free_port()
        env = {**os.environ, 'EZPZ_LIVE_DATABASE_URL': database_url,
               'REALTIME_PUBLIC_URL': f'ws://127.0.0.1:{live_port}/live'}
        cls.api_process = subprocess.Popen([sys.executable, '-B', str(BACKEND_DIR / 'tests' / 'live_server.py')],
                                           stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, env=env)
        line = cls.api_process.stdout.readline()
        if not line.startswith('READY'):
            raise RuntimeError(f'the API did not start: {line!r}')
        cls.api = f'http://127.0.0.1:{line.split()[1]}'
        cls.live = f'ws://127.0.0.1:{live_port}/live'

        cls.live_process = subprocess.Popen(
            [sys.executable, '-B', str(BACKEND_DIR / 'realtime' / 'service.py')],
            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True,
            env={**os.environ, 'DATABASE_URL': database_url, 'REALTIME_API_URL': cls.api, 'REALTIME_PORT': str(live_port)},
        )
        # Wait until it LISTENs, so nothing a test does is announced before anyone could hear it.
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            line = cls.live_process.stderr.readline()
            if 'listening for pack changes' in line:
                break
        else:
            raise RuntimeError('the live service did not start listening')

    @classmethod
    def tearDownClass(cls):
        for process in (cls.live_process, cls.api_process):
            process.kill()
            process.wait()
        admin = psycopg2.connect(SERVER)
        admin.autocommit = True
        with admin.cursor() as cursor:
            cursor.execute(f'DROP DATABASE IF EXISTS {cls.database} WITH (FORCE)')
        admin.close()

    # -- helpers --------------------------------------------------------------

    def person(self, name):
        email = f'{name.lower()}-{uuid.uuid4().hex[:6]}@example.com'
        requests.post(f'{self.api}/__test__/clear-rate-limits')
        requests.post(f'{self.api}/__test__/account', json={'email': email, 'password': PASSWORD, 'name': name}).raise_for_status()
        token = requests.post(f'{self.api}/api/auth/login', json={'email': email, 'password': PASSWORD}).json()['access_token']
        return {'email': email, 'head': {'Authorization': f'Bearer {token}'}, 'token': token}

    def call(self, who, method, path, **kwargs):
        return requests.request(method, f'{self.api}{path}', headers=who['head'], **kwargs)

    def shared_pack(self):
        colin, sam = self.person('Colin'), self.person('Sam')
        pack = self.call(colin, 'post', '/api/packs', json={'name': 'OP DK'}).json()
        self.assertEqual(pack['live_url'], self.live)
        self.call(colin, 'post', f'/api/packs/{pack["uuid"]}/invites', json={'email': sam['email']}).raise_for_status()
        token = requests.get(f'{self.api}/__test__/email', params={'kind': 'pack_invite', 'to': sam['email']}).json()['secret']
        self.call(sam, 'post', '/api/invites/accept', json={'token': token}).raise_for_status()
        return pack['uuid'], colin, sam

    def ops(self, who, pack, *ops):
        body = {'ops': [{'client_op_id': uuid.uuid4().hex, **op} for op in ops]}
        return self.call(who, 'post', f'/api/packs/{pack}/ops', json=body)

    async def open(self, who, pack):
        ws = await connect(self.live)
        await ws.send(json.dumps({'type': 'hello', 'pack': pack, 'token': who['token']}))
        return ws

    async def next(self, ws, kind, seconds=5):
        async def wait():
            while True:
                message = json.loads(await ws.recv())
                if message['type'] == kind:
                    return message
        return await asyncio.wait_for(wait(), seconds)

    async def closed(self, ws):
        reason = (await self.next(ws, 'closed'))['reason']
        with self.assertRaises(ConnectionClosed):
            while True:
                await asyncio.wait_for(ws.recv(), 5)
        return reason, ws.close_code

    # -- the chain --------------------------------------------------------------

    def test_an_edit_reaches_the_other_member_in_order_and_only_once_committed(self):
        pack, colin, sam = self.shared_pack()

        async def run():
            ws = await self.open(sam, pack)
            welcome = await self.next(ws, 'welcome')
            self.assertEqual((welcome['head_seq'], welcome['role']), (3, 'editor'))
            self.assertEqual(self.ops(colin, pack, {'type': 'item.create', 'item': 'lz-1', 'kind': 'lz', 'name': 'LZ HAWK',
                                                    'data': {'flightData': {'landingHeading': 270}}}).status_code, 200)
            self.ops(colin, pack, {'type': 'set', 'item': 'lz-1', 'path': ['flightData', 'landingHeading'], 'value': 90},
                     {'type': 'item.rename', 'item': 'lz-1', 'name': 'LZ EAGLE'}).raise_for_status()
            received = [await self.next(ws, 'event') for _ in range(3)]
            self.assertEqual([m['seq'] for m in received], [4, 5, 6])
            log = self.call(sam, 'get', f'/api/packs/{pack}/events?since=3').json()['events']
            self.assertEqual([m['event'] for m in received], log)          # what the socket says is the log

            # Refused and rolled-back batches are never announced; the next edit is the next number.
            self.assertEqual(self.ops(colin, pack, {'type': 'set', 'item': 'lz-1', 'path': [], 'value': 1}).status_code, 400)
            self.assertEqual(self.ops(colin, pack, {'type': 'set', 'item': 'lz-1', 'path': ['notes'], 'value': 'x' * (6 * 1024 * 1024)}).status_code, 413)
            self.ops(colin, pack, {'type': 'item.rename', 'item': 'lz-1', 'name': 'LZ CROW'}).raise_for_status()
            self.assertEqual((await self.next(ws, 'event'))['seq'], 7)

            # An event too large for a NOTIFY arrives as a number to fetch.
            self.ops(colin, pack, {'type': 'set', 'item': 'lz-1', 'path': ['notes'], 'value': 'x' * 9000}).raise_for_status()
            self.assertEqual(await self.next(ws, 'head'), {'type': 'head', 'pack': pack, 'seq': 8})
            await ws.close()

        asyncio.run(run())

    def test_someone_removed_from_the_pack_is_cut_off_and_a_deleted_pack_closes(self):
        pack, colin, sam = self.shared_pack()

        async def run():
            colin_ws, sam_ws = await self.open(colin, pack), await self.open(sam, pack)
            await self.next(colin_ws, 'welcome')
            await self.next(sam_ws, 'welcome')
            me = self.call(sam, 'get', '/api/auth/me').json()
            self.call(colin, 'delete', f'/api/packs/{pack}/members/{me["id"]}').raise_for_status()
            self.assertEqual(await self.closed(sam_ws), ('not_in_pack', 4404))
            self.call(colin, 'delete', f'/api/packs/{pack}').raise_for_status()
            self.assertEqual(await self.closed(colin_ws), ('pack_gone', 4410))

        asyncio.run(run())

    def test_people_see_each_other(self):
        pack, colin, sam = self.shared_pack()

        async def run():
            colin_ws, sam_ws = await self.open(colin, pack), await self.open(sam, pack)
            await self.next(colin_ws, 'welcome')
            await self.next(sam_ws, 'welcome')
            await sam_ws.send(json.dumps({'type': 'presence', 'focus': {'item': 'lz-1'}}))
            while True:
                people = (await self.next(colin_ws, 'presence'))['people']
                if any(p['focus'] for p in people):
                    break
            self.assertEqual(sorted(p['name'] for p in people), ['Colin', 'Sam'])
            await colin_ws.close()
            await sam_ws.close()

        asyncio.run(run())

    def test_a_stranger_is_refused_by_the_real_api(self):
        pack, _colin, _sam = self.shared_pack()
        stranger = self.person('Alex')

        async def run():
            ws = await self.open(stranger, pack)
            self.assertEqual(await self.closed(ws), ('not_in_pack', 4404))
            ws = await self.open({'token': 'not-a-token'}, pack)
            self.assertEqual(await self.closed(ws), ('unauthorized', 4401))

        asyncio.run(run())


if __name__ == '__main__':
    unittest.main()
