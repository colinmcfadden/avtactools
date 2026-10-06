"""What one edit does to a mission pack's items: the server's copy of the rules.

Every member's edits arrive as small operations addressed by stable ids ("move
helicopter h-3 on diagram d-1"), never by array position. The pack routes apply
them one at a time per pack, in the order they are numbered, so two people
editing different things never collide and two editing the same field leave the
later one standing. Nothing here knows what an LZ or a route is: it only follows
paths through JSON.

The web's ``frontend/src/feature/missionPacks/packOps.js`` is the reference, and
this is held to every case it writes in ``contracts/fixtures/packs/ops.json``
(``tests/test_pack_ops.py``). Change the rules there first. See
docs/MISSION_PACKS.md.

``items`` maps an item's uuid to ``{kind, name, data, deleted}``. ``apply``
changes it in place, and only when the operation is applied: everything that
could make it skip is checked before anything is touched, so a skipped or
malformed operation leaves the items exactly as they were. Stdlib only.
"""

import copy
import math
import re

ITEM_KINDS = ('lz', 'route', 'pointset')
# What a client may send. ``item.replace`` is the server's own (update from original).
CLIENT_OP_TYPES = ('item.create', 'item.delete', 'item.rename', 'set', 'patch', 'upsert', 'insert', 'remove')
SERVER_OP_TYPES = ('item.replace',)
MAX_NAME_LENGTH = 100

APPLIED = 'applied'
SKIPPED = 'skipped'
INVALID = 'invalid'

_ITEM_ID = re.compile(r'^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}\Z')
_NOT_BLANK = re.compile(r'[^ \t\n\r]')
# Keys that would reach an object's prototype in JavaScript. Harmless here, but
# refused everywhere so every client agrees on what is malformed.
_FORBIDDEN_KEYS = ('__proto__', 'constructor', 'prototype')


def _is_object(value):
    return isinstance(value, dict)


def _is_id(value):
    """An element's id: text or a number (never a boolean, which Python counts as an int)."""
    if isinstance(value, bool):
        return False
    if isinstance(value, str):
        return True
    return isinstance(value, (int, float)) and math.isfinite(value)


def _same_id(a, b):
    """JavaScript's ``===`` for ids: the number 1 is not the text "1", but 1 and 1.0 are one number."""
    if isinstance(a, bool) or isinstance(b, bool):
        return False
    if isinstance(a, str) or isinstance(b, str):
        return isinstance(a, str) and isinstance(b, str) and a == b
    return isinstance(a, (int, float)) and isinstance(b, (int, float)) and a == b


def _is_key_segment(segment):
    return isinstance(segment, str) and segment not in _FORBIDDEN_KEYS


def _is_id_segment(segment):
    return isinstance(segment, dict) and list(segment) == ['id'] and _is_id(segment['id'])


def _is_name(name):
    # Counted in code points, as the web counts them (Array.from) and Kotlin will.
    return isinstance(name, str) and bool(_NOT_BLANK.search(name)) and len(name) <= MAX_NAME_LENGTH


def _find(array, element_id):
    for index, element in enumerate(array):
        if isinstance(element, dict) and 'id' in element and _same_id(element['id'], element_id):
            return index
    return -1


def validate(op):
    """Why ``op`` is malformed, or None. The checks run in the reference's order, so the reason agrees with it."""
    if not _is_object(op):
        return 'bad_op'
    kind_of_op = op.get('type')
    if not isinstance(kind_of_op, str) or kind_of_op not in CLIENT_OP_TYPES + SERVER_OP_TYPES:
        return 'unknown_type'
    item = op.get('item')
    if not isinstance(item, str) or not _ITEM_ID.match(item):
        return 'bad_item'

    if kind_of_op == 'item.create':
        if op.get('kind') not in ITEM_KINDS:
            return 'bad_kind'
        if not _is_name(op.get('name')):
            return 'bad_name'
        data = op.get('data')
        if (op['kind'] == 'pointset' and not isinstance(data, list)) or (op['kind'] != 'pointset' and not _is_object(data)):
            return 'bad_data'
        return None
    if kind_of_op == 'item.rename':
        return None if _is_name(op.get('name')) else 'bad_name'
    if kind_of_op == 'item.delete':
        return None
    if kind_of_op == 'item.replace':
        return None if isinstance(op.get('data'), (dict, list)) else 'bad_data'

    path = op.get('path')
    if not isinstance(path, list) or not all(_is_key_segment(s) or _is_id_segment(s) for s in path):
        return 'bad_path'
    last = path[-1] if path else None
    value = op.get('value')
    if kind_of_op == 'set':
        if not path:
            return 'bad_path'
        if 'value' not in op:
            return 'bad_value'
        # Setting an element whole keeps its identity.
        if _is_id_segment(last) and not (_is_object(value) and 'id' in value and _same_id(value['id'], last['id'])):
            return 'bad_value'
        return None
    if kind_of_op == 'patch':
        if not _is_object(value):
            return 'bad_value'
        if _is_id_segment(last) and 'id' in value and not _same_id(value['id'], last['id']):
            return 'bad_value'
        return None
    if kind_of_op == 'upsert':
        return None if _is_object(value) and 'id' in value and _is_id(value['id']) else 'bad_value'
    if kind_of_op == 'insert':
        if 'after' not in op or not (op['after'] is None or _is_id(op['after'])):
            return 'bad_after'
        return None if _is_object(value) and 'id' in value and _is_id(value['id']) else 'bad_value'
    # remove
    return None if _is_id_segment(last) else 'bad_path'


class _Skip(Exception):
    def __init__(self, reason):
        super().__init__(reason)
        self.reason = reason


def _follow(root, segments):
    """Where ``segments`` lead from ``root``, creating nothing. A missing or null key is "target_missing"."""
    current = root
    for segment in segments:
        if isinstance(segment, str):
            if not _is_object(current):
                raise _Skip('not_an_object')
            if current.get(segment) is None:
                raise _Skip('target_missing')
            current = current[segment]
        else:
            if not isinstance(current, list):
                raise _Skip('not_an_array')
            index = _find(current, segment['id'])
            if index < 0:
                raise _Skip('target_missing')
            current = current[index]
    return current


def _set(root, path, value):
    """Sets a field, making objects that are missing (or null) on the way, unless an id must be found in one."""
    parent_path, last = path[:-1], path[-1]
    current = root
    for i, segment in enumerate(parent_path):
        if isinstance(segment, str):
            if not _is_object(current):
                raise _Skip('not_an_object')
            if current.get(segment) is None:
                if not all(isinstance(s, str) for s in path[i + 1:]):
                    raise _Skip('target_missing')
                for key in parent_path[i:]:
                    current[key] = {}
                    current = current[key]
                current[last] = copy.deepcopy(value)
                return
            current = current[segment]
        else:
            if not isinstance(current, list):
                raise _Skip('not_an_array')
            index = _find(current, segment['id'])
            if index < 0:
                raise _Skip('target_missing')
            current = current[index]
    if isinstance(last, str):
        if not _is_object(current):
            raise _Skip('not_an_object')
        current[last] = copy.deepcopy(value)
        return
    if not isinstance(current, list):
        raise _Skip('not_an_array')
    index = _find(current, last['id'])
    if index < 0:
        raise _Skip('target_missing')
    current[index] = copy.deepcopy(value)


def _patch(root, path, value):
    target = _follow(root, path)
    if not _is_object(target):
        raise _Skip('not_an_object')
    target.update(copy.deepcopy(value))


def _array_at(root, path):
    """The array a path names: ``(array, make)``, where ``make`` creates it when its last key is missing or null."""
    if not path:
        if not isinstance(root, list):
            raise _Skip('not_an_array')
        return root, None
    last = path[-1]
    parent = _follow(root, path[:-1])
    if isinstance(last, str):
        if not _is_object(parent):
            raise _Skip('not_an_object')
        existing = parent.get(last)
        if existing is None:
            def make():
                parent[last] = []
                return parent[last]
            return [], make
        if not isinstance(existing, list):
            raise _Skip('not_an_array')
        return existing, None
    _follow(parent, [last])
    raise _Skip('not_an_array')


def _upsert(root, path, value):
    array, make = _array_at(root, path)
    if make:
        array = make()
    index = _find(array, value['id'])
    if index < 0:
        array.append(copy.deepcopy(value))
    else:
        merged = dict(array[index])
        merged.update(copy.deepcopy(value))
        array[index] = merged


def _insert(root, path, after, value):
    """After the element with id ``after``; first when ``after`` is None; last when that element is gone."""
    array, make = _array_at(root, path)
    if _find(array, value['id']) >= 0:
        raise _Skip('element_exists')
    if make:
        array = make()
    if after is None:
        array.insert(0, copy.deepcopy(value))
        return
    index = _find(array, after)
    if index < 0:
        array.append(copy.deepcopy(value))
    else:
        array.insert(index + 1, copy.deepcopy(value))


def _remove(root, path):
    array = _follow(root, path[:-1])
    if not isinstance(array, list):
        raise _Skip('not_an_array')
    index = _find(array, path[-1]['id'])
    if index < 0:
        raise _Skip('target_missing')
    del array[index]


_EDITS = {
    'set': lambda data, op: _set(data, op['path'], op['value']),
    'patch': lambda data, op: _patch(data, op['path'], op['value']),
    'upsert': lambda data, op: _upsert(data, op['path'], op['value']),
    'insert': lambda data, op: _insert(data, op['path'], op['after'], op['value']),
    'remove': lambda data, op: _remove(data, op['path']),
}


def apply(items, op):
    """Applies one operation to ``items`` in place: ``(status, reason)``.

    ``status`` is ``applied``, ``skipped`` (well formed, but what it edits is gone
    or in the way; nothing changed) or ``invalid`` (malformed; see ``validate``).
    """
    invalid = validate(op)
    if invalid:
        return INVALID, invalid

    uuid = op['item']
    current = items.get(uuid)
    if op['type'] == 'item.create':
        # A deleted item's uuid is never reused.
        if current is not None:
            return SKIPPED, 'item_exists'
        items[uuid] = {'kind': op['kind'], 'name': op['name'], 'data': copy.deepcopy(op['data']), 'deleted': False}
        return APPLIED, None
    if current is None or current.get('deleted'):
        return SKIPPED, 'item_missing'

    if op['type'] == 'item.rename':
        current['name'] = op['name']
        return APPLIED, None
    if op['type'] == 'item.delete':
        # Deleted means gone: the content is not kept.
        current.update(name='', data=None, deleted=True)
        return APPLIED, None
    if op['type'] == 'item.replace':
        if current['kind'] == 'pointset' and not isinstance(op['data'], list):
            return SKIPPED, 'not_an_array'
        if current['kind'] != 'pointset' and not _is_object(op['data']):
            return SKIPPED, 'not_an_object'
        current['data'] = copy.deepcopy(op['data'])
        return APPLIED, None

    try:
        _EDITS[op['type']](current['data'], op)
    except _Skip as skip:
        return SKIPPED, skip.reason
    return APPLIED, None
