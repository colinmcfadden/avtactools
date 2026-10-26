"""Opaque names for streamed surveys and their pieces.

A streamed tileset's URL keeps the shape the clients already parse,
``/lidar/tilesets/<16 hex>/<file>``, and says nothing about where it is:

- a survey's key is an HMAC of its name under ``LIDAR_KEY_SECRET``, found
  again by computing it for every survey in USGS's index (a few thousand);
- a piece's name is its octree address put through a keyed permutation of
  64-bit numbers (a Feistel network over HMAC rounds), so the server reads the
  address back out of the name without keeping a table of names handed out.
  An address is a place to within the piece's size, which is why it is never
  written in the clear.
"""

from __future__ import annotations

import hashlib
import hmac
import re

from . import catalog
from .ept import MAX_DEPTH, Node

_NAME = re.compile(r"^[0-9a-f]{16}$")
_ROUNDS = 8
_HALF = 0xFFFFFFFF
_COORD_BITS = 19


def survey_key(survey: str, *, secret: str | None = None) -> str:
    secret = secret if secret is not None else catalog.key_secret()
    return hmac.new(secret.encode(), f"survey:{survey}".encode(),
                    hashlib.sha256).hexdigest()[:16]


def _round(secret: bytes, survey: str, index: int, half: int) -> int:
    digest = hmac.new(secret, f"piece:{survey}:{index}:{half}".encode(),
                      hashlib.sha256).digest()
    return int.from_bytes(digest[:4], "big")


def _pack(node: Node) -> int:
    return ((node.depth << (3 * _COORD_BITS)) | (node.x << (2 * _COORD_BITS))
            | (node.y << _COORD_BITS) | node.z)


def _unpack(value: int) -> Node | None:
    mask = (1 << _COORD_BITS) - 1
    if value >> (3 * _COORD_BITS + 5):
        return None
    node = Node(value >> (3 * _COORD_BITS), (value >> (2 * _COORD_BITS)) & mask,
                (value >> _COORD_BITS) & mask, value & mask)
    return node if node.valid else None


def piece_name(survey: str, node: Node, *, secret: str | None = None) -> str:
    if not node.valid or node.depth > MAX_DEPTH:
        raise ValueError(f"no name for {node}")
    key = (secret if secret is not None else catalog.key_secret()).encode()
    value = _pack(node)
    left, right = value >> 32, value & _HALF
    for index in range(_ROUNDS):
        left, right = right, left ^ _round(key, survey, index, right)
    return f"{(left << 32) | right:016x}"


def piece_node(survey: str, name: str, *, secret: str | None = None) -> Node | None:
    """The node a name was given to, or None for a name never handed out."""
    if not _NAME.match(name or ""):
        return None
    key = (secret if secret is not None else catalog.key_secret()).encode()
    value = int(name, 16)
    left, right = value >> 32, value & _HALF
    for index in reversed(range(_ROUNDS)):
        left, right = right ^ _round(key, survey, index, left), left
    return _unpack((left << 32) | right)
