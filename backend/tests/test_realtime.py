"""The live service's protocol, over real sockets, with the API's access check stood in for.

Needs ``websockets`` (backend/realtime/requirements.txt); skipped without it.
tests/test_realtime_live.py runs the whole chain against a real Postgres.
"""

import asyncio
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

try:
    from websockets.asyncio.client import connect
    from websockets.asyncio.server import serve
    from websockets.exceptions import ConnectionClosed
except ImportError:  # pragma: no cover - depends on the environment
    raise unittest.SkipTest('websockets is not installed (pip install -r realtime/requirements.txt)')

from realtime import service  # noqa: E402

PACK = '6f1c9a52-0000-4000-8000-000000000001'
OTHER = '6f1c9a52-0000-4000-8000-000000000002'


class FakeAccess:
    """The API's answer to "may this token see this pack?", kept in memory."""

    def __init__(self):
        self.people = {
            'colin-token': ({'id': 1, 'name': 'Colin'}, {PACK: 'owner', OTHER: 'owner'}),
            'sam-token': ({'id': 2, 'name': 'Sam'}, {PACK: 'editor'}),
            'alex-token': ({'id': 3, 'name': 'Alex'}, {OTHER: 'viewer'}),
        }
        self.heads = {PACK: 7, OTHER: 2}
        self.gate = None
        self.down = False
        self.calls = []

    async def check(self, token, pack):
        self.calls.append((token, pack))
        if self.gate is not None:
            await self.gate.wait()
        if self.down:
            raise service.Refused('unavailable')
        if token not in self.people:
            raise service.Refused('unauthorized')
        user, packs = self.people[token]
        if pack not in packs:
            raise service.Refused('not_in_pack')
        return {'role': packs[pack], 'status': 'active', 'head_seq': self.heads[pack], 'user': user}


def event(seq, op_type='set', **extra):
    return {'seq': seq, 'type': op_type, 'item': 'lz-1', 'actor': {'id': 1, 'name': 'Colin'}, 'summary': '',
            'status': 'applied', 'reason': None, 'client_op_id': f'op-{seq}', 'op': {'type': op_type, **extra},
            'created_at': '2026-10-05T12:00:00'}


class LiveServiceCase(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        patcher = patch.object(service, 'PRESENCE_SECONDS', 0.01)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.access = FakeAccess()
        self.hub = service.Hub(self.access)
        self.server = await serve(self.hub.serve_socket, '127.0.0.1', 0, process_request=service.health,
                                  max_size=service.MAX_FRAME)
        self.port = self.server.sockets[0].getsockname()[1]
        self.presence = asyncio.create_task(self.hub.presence_forever())
        self.sockets = []

    async def asyncTearDown(self):
        for ws in self.sockets:
            await ws.close()
        self.presence.cancel()
        self.server.close()
        await self.server.wait_closed()

    async def open(self, token='colin-token', pack=PACK, path='/live'):
        ws = await connect(f'ws://127.0.0.1:{self.port}{path}')
        self.sockets.append(ws)
        await ws.send(json.dumps({'type': 'hello', 'pack': pack, 'token': token}))
        return ws

    async def joined(self, token='colin-token', pack=PACK):
        ws = await self.open(token, pack)
        welcome = await self.next(ws, 'welcome')
        return ws, welcome

    async def next(self, ws, kind, seconds=2):
        """The next message of one kind, passing over the others (presence arrives whenever it likes)."""
        async def wait():
            while True:
                message = json.loads(await ws.recv())
                if message['type'] == kind:
                    return message
        return await asyncio.wait_for(wait(), seconds)

    async def nothing(self, ws, kind, seconds=0.3):
        with self.assertRaises(asyncio.TimeoutError):
            await self.next(ws, kind, seconds)

    async def closed_with(self, ws, code, reason):
        message = await self.next(ws, 'closed')
        self.assertEqual(message['reason'], reason)
        with self.assertRaises(ConnectionClosed):
            while True:
                await asyncio.wait_for(ws.recv(), 2)
        self.assertEqual(ws.close_code, code)


class HelloTests(LiveServiceCase):
    async def test_a_member_is_welcomed_with_the_packs_head_and_who_they_are(self):
        _ws, welcome = await self.joined('sam-token')
        self.assertEqual((welcome['pack'], welcome['head_seq'], welcome['role'], welcome['status']), (PACK, 7, 'editor', 'active'))
        self.assertEqual((welcome['you']['user_id'], welcome['you']['name']), (2, 'Sam'))
        self.assertEqual(len(welcome['you']['session']), 16)
        self.assertEqual(self.access.calls, [('sam-token', PACK)])

    async def test_a_token_the_api_refuses_is_closed_4401(self):
        await self.closed_with(await self.open('stale-token'), 4401, 'unauthorized')

    async def test_a_pack_the_person_is_not_in_is_closed_4404(self):
        await self.closed_with(await self.open('alex-token', PACK), 4404, 'not_in_pack')

    async def test_an_api_that_cannot_answer_closes_1013_to_try_later(self):
        self.access.down = True
        await self.closed_with(await self.open(), 1013, 'unavailable')

    async def test_a_hello_that_is_not_one_is_closed_4400(self):
        for hello in ('nonsense', json.dumps([1]), json.dumps({'type': 'hi', 'pack': PACK, 'token': 'x'}),
                      json.dumps({'type': 'hello', 'pack': PACK}), json.dumps({'type': 'hello', 'pack': 'x' * 37, 'token': 't'})):
            with self.subTest(hello=hello):
                ws = await connect(f'ws://127.0.0.1:{self.port}/live')
                self.sockets.append(ws)
                await ws.send(hello)
                await self.closed_with(ws, 4400, 'bad_hello')
        self.assertEqual(self.access.calls, [])

    async def test_no_hello_in_time_is_closed_4408(self):
        with patch.object(service, 'HELLO_SECONDS', 0.1):
            ws = await connect(f'ws://127.0.0.1:{self.port}/live')
            self.sockets.append(ws)
            await self.closed_with(ws, 4408, 'hello_timeout')

    async def test_health_and_unknown_paths_are_plain_http(self):
        reader, writer = await asyncio.open_connection('127.0.0.1', self.port)
        writer.write(b'GET /health HTTP/1.1\r\nHost: x\r\n\r\n')
        await writer.drain()
        self.assertIn(b'200 OK', await reader.read(200))
        writer.close()
        with self.assertRaises(Exception):
            await connect(f'ws://127.0.0.1:{self.port}/elsewhere')


class DeliveryTests(LiveServiceCase):
    async def test_an_announced_event_reaches_everyone_with_that_pack_open_and_no_one_else(self):
        colin, _ = await self.joined('colin-token')
        sam, _ = await self.joined('sam-token')
        alex, _ = await self.joined('alex-token', OTHER)
        self.hub.deliver_text(json.dumps({'pack': PACK, 'seq': 8, 'event': event(8, path=['name'], value='X')}))
        for ws in (colin, sam):
            message = await self.next(ws, 'event')
            self.assertEqual((message['pack'], message['seq'], message['event']['op']['value']), (PACK, 8, 'X'))
        await self.nothing(alex, 'event')

    async def test_events_arrive_in_the_order_announced(self):
        sam, _ = await self.joined('sam-token')
        for seq in range(8, 40):
            self.hub.deliver_text(json.dumps({'pack': PACK, 'seq': seq, 'event': event(seq)}))
        self.assertEqual([(await self.next(sam, 'event'))['seq'] for _ in range(8, 40)], list(range(8, 40)))

    async def test_an_event_announced_without_its_body_is_a_head_to_fetch(self):
        sam, _ = await self.joined('sam-token')
        self.hub.deliver_text(json.dumps({'pack': PACK, 'seq': 8}))
        self.assertEqual(await self.next(sam, 'head'), {'type': 'head', 'pack': PACK, 'seq': 8})

    async def test_what_is_announced_while_the_api_is_asked_is_held_until_it_says_yes(self):
        self.access.gate = asyncio.Event()
        sam = await self.open('sam-token')
        while not self.access.calls:
            await asyncio.sleep(0.01)
        self.hub.deliver_text(json.dumps({'pack': PACK, 'seq': 8, 'event': event(8)}))
        await self.nothing(sam, 'event')
        self.access.gate.set()
        first = json.loads(await asyncio.wait_for(sam.recv(), 2))
        self.assertEqual(first['type'], 'welcome')
        self.assertEqual((await self.next(sam, 'event'))['seq'], 8)

    async def test_nothing_held_reaches_someone_the_api_refuses(self):
        self.access.gate = asyncio.Event()
        alex = await self.open('alex-token', PACK)
        while not self.access.calls:
            await asyncio.sleep(0.01)
        self.hub.deliver_text(json.dumps({'pack': PACK, 'seq': 8, 'event': event(8)}))
        self.access.gate.set()
        kinds = []
        with self.assertRaises(ConnectionClosed):
            while True:
                kinds.append(json.loads(await asyncio.wait_for(alex.recv(), 2))['type'])
        self.assertEqual(kinds, ['closed'])

    async def test_a_deleted_pack_closes_its_sockets_4410(self):
        colin, _ = await self.joined('colin-token')
        other, _ = await self.joined('colin-token', OTHER)
        self.hub.deliver_text(json.dumps({'pack': PACK, 'deleted': True}))
        await self.closed_with(colin, 4410, 'pack_gone')
        self.hub.deliver_text(json.dumps({'pack': OTHER, 'seq': 3, 'event': event(3)}))
        self.assertEqual((await self.next(other, 'event'))['seq'], 3)

    async def test_a_lost_listen_connection_tells_everyone_to_catch_up(self):
        sam, _ = await self.joined('sam-token')
        self.hub.resync_all()
        self.assertEqual(await self.next(sam, 'resync'), {'type': 'resync', 'pack': PACK})

    async def test_announcements_that_make_no_sense_are_ignored(self):
        sam, _ = await self.joined('sam-token')
        for text in ('nonsense', '[]', '{}', json.dumps({'pack': PACK, 'seq': 'x'}), json.dumps({'pack': 5, 'seq': 1})):
            self.hub.deliver_text(text)
        self.hub.deliver_text(json.dumps({'pack': PACK, 'seq': 8, 'event': event(8)}))
        self.assertEqual((await self.next(sam, 'event'))['seq'], 8)


class MembershipTests(LiveServiceCase):
    async def test_someone_removed_from_the_pack_is_closed_and_the_rest_stay(self):
        colin, _ = await self.joined('colin-token')
        sam, _ = await self.joined('sam-token')
        del self.access.people['sam-token'][1][PACK]
        self.hub.deliver_text(json.dumps({'pack': PACK, 'seq': 8, 'event': event(8, 'member.remove', user_id=2)}))
        await self.closed_with(sam, 4404, 'not_in_pack')
        self.hub.deliver_text(json.dumps({'pack': PACK, 'seq': 9, 'event': event(9)}))
        # The membership change is itself an event everyone left in the pack sees.
        self.assertEqual([(await self.next(colin, 'event'))['seq'] for _ in range(2)], [8, 9])

    async def test_an_ordinary_edit_asks_the_api_nothing(self):
        await self.joined('sam-token')
        self.hub.deliver_text(json.dumps({'pack': PACK, 'seq': 8, 'event': event(8)}))
        await asyncio.sleep(0.1)
        self.assertEqual(len(self.access.calls), 1)

    async def test_an_api_that_is_down_during_a_recheck_keeps_everyone(self):
        sam, _ = await self.joined('sam-token')
        self.access.down = True
        self.hub.deliver_text(json.dumps({'pack': PACK, 'seq': 8, 'event': event(8, 'member.role')}))
        await asyncio.sleep(0.1)
        self.access.down = False
        self.hub.deliver_text(json.dumps({'pack': PACK, 'seq': 9, 'event': event(9)}))
        self.assertEqual([(await self.next(sam, 'event'))['seq'] for _ in range(2)], [8, 9])

    async def test_a_refreshed_token_is_the_one_checked_next(self):
        sam, _ = await self.joined('sam-token')
        self.access.people['sam-token-2'] = self.access.people.pop('sam-token')
        await sam.send(json.dumps({'type': 'token', 'token': 'sam-token-2'}))
        await asyncio.sleep(0.05)
        self.hub.deliver_text(json.dumps({'pack': PACK, 'seq': 8, 'event': event(8, 'pack.share')}))
        await asyncio.sleep(0.1)
        self.assertEqual(self.access.calls[-1], ('sam-token-2', PACK))
        self.hub.deliver_text(json.dumps({'pack': PACK, 'seq': 9, 'event': event(9)}))
        self.assertEqual([(await self.next(sam, 'event'))['seq'] for _ in range(2)], [8, 9])


class PresenceTests(LiveServiceCase):
    async def test_everyone_sees_who_is_here_and_what_they_have_open(self):
        colin, colin_welcome = await self.joined('colin-token')
        sam, sam_welcome = await self.joined('sam-token')
        await sam.send(json.dumps({'type': 'presence', 'focus': {'item': 'lz-1', 'graphic': 'h-3'}}))

        async def until(ws, test):
            while True:
                message = await self.next(ws, 'presence')
                if test(message['people']):
                    return message['people']
        people = await asyncio.wait_for(until(colin, lambda p: any(x['focus'] for x in p)), 2)
        self.assertEqual(sorted((p['name'], json.dumps(p['focus'])) for p in people),
                         [('Colin', 'null'), ('Sam', '{"item": "lz-1", "graphic": "h-3"}')])
        self.assertEqual({p['session'] for p in people}, {colin_welcome['you']['session'], sam_welcome['you']['session']})
        await sam.close()
        people = await asyncio.wait_for(until(colin, lambda p: len(p) == 1), 2)
        self.assertEqual(people[0]['name'], 'Colin')

    async def test_a_focus_too_large_or_not_an_object_is_ignored(self):
        colin, _ = await self.joined('colin-token')
        for focus in ({'x': 'y' * 2000}, 'lz-1', [1]):
            await colin.send(json.dumps({'type': 'presence', 'focus': focus}))
        await colin.send(json.dumps({'type': 'presence', 'focus': {'item': 'ok'}}))
        while True:
            people = (await self.next(colin, 'presence'))['people']
            if people[0]['focus'] is not None:
                break
        self.assertEqual(people[0]['focus'], {'item': 'ok'})

    async def test_a_frame_over_the_limit_closes_the_socket(self):
        colin, _ = await self.joined('colin-token')
        await colin.send('x' * (service.MAX_FRAME + 1))
        with self.assertRaises(ConnectionClosed):
            while True:
                await asyncio.wait_for(colin.recv(), 2)
        self.assertEqual(colin.close_code, 1009)


class PlainTests(unittest.TestCase):
    def test_a_member_too_far_behind_is_refused_more(self):
        async def run():
            member = service.Member(ws=None)
            member.ready = True
            accepted = [member.put({'n': n}) for n in range(service.MAX_QUEUE + 1)]
            return accepted
        accepted = asyncio.run(run())
        self.assertTrue(all(accepted[:-1]))
        self.assertFalse(accepted[-1])

    def test_the_database_url_is_made_ready_for_libpq(self):
        self.assertEqual(service.dsn_from('postgresql+psycopg2://u:p@h:5432/d'), 'postgresql://u:p@h:5432/d')
        self.assertEqual(service.dsn_from(' postgres://u@h/d '), 'postgres://u@h/d')
        self.assertEqual(service.dsn_from('postgresql://u@h/d'), 'postgresql://u@h/d')

    def test_it_will_not_start_without_postgres_and_the_api(self):
        for environ in ({}, {'REALTIME_API_URL': 'http://api'}, {'REALTIME_API_URL': 'http://api', 'DATABASE_URL': 'sqlite:///x.db'}):
            with self.assertRaises(SystemExit):
                asyncio.run(service.run(environ))


if __name__ == '__main__':
    unittest.main()
