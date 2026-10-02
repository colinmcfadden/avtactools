"""Short-lived, single-process storage for route share links (QR fallback).

When a route has too many points to fit ForeFlight's deep link inside a QR code,
the app stores the route's GPX + Garmin FPL server-side and the QR encodes only
an opaque token URL. Scanning it opens a small landing page offering both files.

Unlike the threat download store, a share is fetched several times per token (the
landing page plus each file download), so entries are TTL-bounded rather than
consumed after N downloads. Process-local by design: the Fly deployment runs a
single worker. A shared store (e.g. Redis) is required before scaling out.
"""

from __future__ import annotations

import hashlib
import secrets
import threading
import time
from collections import OrderedDict
from dataclasses import dataclass
from typing import Callable, Optional


@dataclass(frozen=True)
class RouteShare:
    name: str
    gpx: bytes
    fpl: bytes


@dataclass
class _StoredShare:
    share: RouteShare
    expires_at: float
    size: int


class RouteShareStore:
    """Thread-safe TTL store with bounded entry count and total size."""

    def __init__(
        self,
        *,
        ttl_seconds: int = 1800,
        max_entries: int = 256,
        max_entry_bytes: int = 1024 * 1024,
        max_total_bytes: int = 32 * 1024 * 1024,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        if (
            ttl_seconds <= 0
            or max_entries <= 0
            or max_entry_bytes <= 0
            or max_total_bytes <= 0
            or max_entry_bytes > max_total_bytes
        ):
            raise ValueError("Store limits must be positive and internally consistent")
        self.ttl_seconds = ttl_seconds
        self.max_entries = max_entries
        self.max_entry_bytes = max_entry_bytes
        self.max_total_bytes = max_total_bytes
        self._clock = clock
        self._entries: "OrderedDict[str, _StoredShare]" = OrderedDict()
        self._total_bytes = 0
        self._lock = threading.Lock()

    @staticmethod
    def _key(token: str) -> str:
        return hashlib.sha256(token.encode("ascii")).hexdigest()

    def _remove(self, key: str) -> None:
        entry = self._entries.pop(key, None)
        if entry is not None:
            self._total_bytes -= entry.size

    def _prune_expired(self, now: float) -> None:
        for key in [k for k, e in self._entries.items() if e.expires_at <= now]:
            self._remove(key)

    def create(self, name: str, gpx: bytes, fpl: bytes) -> str:
        gpx, fpl = bytes(gpx), bytes(fpl)
        size = len(gpx) + len(fpl)
        if size > self.max_entry_bytes:
            raise ValueError("Route share exceeds the per-link storage limit")
        token = secrets.token_urlsafe(24)
        key = self._key(token)
        now = self._clock()
        with self._lock:
            self._prune_expired(now)
            while self._entries and (
                len(self._entries) >= self.max_entries
                or self._total_bytes + size > self.max_total_bytes
            ):
                self._remove(next(iter(self._entries)))
            self._entries[key] = _StoredShare(
                share=RouteShare(name=name, gpx=gpx, fpl=fpl),
                expires_at=now + self.ttl_seconds,
                size=size,
            )
            self._total_bytes += size
        return token

    def get(self, token: str) -> Optional[RouteShare]:
        if not isinstance(token, str) or not token or len(token) > 128:
            return None
        try:
            key = self._key(token)
        except UnicodeEncodeError:
            return None
        now = self._clock()
        with self._lock:
            self._prune_expired(now)
            entry = self._entries.get(key)
            return entry.share if entry is not None else None

    def clear(self) -> None:
        with self._lock:
            self._entries.clear()
            self._total_bytes = 0
