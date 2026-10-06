"""The server applies pack operations exactly as the web's reference does (contracts/fixtures/packs/ops.json)."""

import copy
import json
import sys
import unittest
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

import pack_ops  # noqa: E402

FIXTURE = Path(__file__).resolve().parents[2] / 'contracts' / 'fixtures' / 'packs' / 'ops.json'
CASES = json.loads(FIXTURE.read_text(encoding='utf-8'))['cases']


class FixtureTests(unittest.TestCase):
    def test_every_case(self):
        for case in CASES:
            with self.subTest(case['name']):
                items = copy.deepcopy(case['items'])
                status, reason = pack_ops.apply(items, case['op'])
                self.assertEqual((status, reason), (case['status'], case['reason']))
                self.assertEqual(items, case['expected'])

    def test_validation_agrees_with_every_case(self):
        for case in CASES:
            with self.subTest(case['name']):
                expected = case['reason'] if case['status'] == 'invalid' else None
                self.assertEqual(pack_ops.validate(case['op']), expected)

    def test_the_operation_is_never_changed_and_never_shared_with_the_items(self):
        for case in CASES:
            with self.subTest(case['name']):
                op = copy.deepcopy(case['op'])
                items = copy.deepcopy(case['items'])
                pack_ops.apply(items, op)
                self.assertEqual(op, case['op'])
                if pack_ops.apply(copy.deepcopy(case['items']), op)[0] == pack_ops.APPLIED and isinstance(op, dict):
                    # Changing the operation afterwards must not reach into the items.
                    before = copy.deepcopy(items)
                    for key in ('value', 'data'):
                        if isinstance(op.get(key), dict):
                            op[key]['mutated'] = True
                        elif isinstance(op.get(key), list):
                            op[key].append('mutated')
                    self.assertEqual(items, before)


class PythonSpecificTests(unittest.TestCase):
    """What the fixture cannot say because JSON and JavaScript have no such values."""

    def test_a_boolean_is_not_an_id_even_though_python_counts_it_as_one(self):
        items = {'lz-1': {'kind': 'lz', 'name': 'X', 'deleted': False, 'data': {'units': [{'id': True}, {'id': 1}]}}}
        status, _ = pack_ops.apply(items, {'type': 'patch', 'item': 'lz-1', 'path': ['units', {'id': 1}], 'value': {'hit': 1}})
        self.assertEqual(status, pack_ops.APPLIED)
        self.assertEqual(items['lz-1']['data']['units'], [{'id': True}, {'id': 1, 'hit': 1}])

    def test_one_and_one_point_oh_are_the_same_id_as_in_javascript(self):
        items = {'ps-1': {'kind': 'pointset', 'name': 'X', 'deleted': False, 'data': [{'id': 1.0}]}}
        self.assertEqual(pack_ops.apply(items, {'type': 'remove', 'item': 'ps-1', 'path': [{'id': 1}]}),
                         (pack_ops.APPLIED, None))

    def test_an_item_id_ending_in_a_newline_is_malformed(self):
        # Python's $ would match before a trailing newline; JavaScript's does not.
        self.assertEqual(pack_ops.validate({'type': 'item.delete', 'item': 'lz-1\n'}), 'bad_item')

    def test_infinity_is_not_an_id(self):
        self.assertEqual(pack_ops.validate({'type': 'remove', 'item': 'ps-1', 'path': [{'id': float('inf')}]}), 'bad_path')


if __name__ == '__main__':
    unittest.main()
