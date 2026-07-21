"""Short-lived, single-process storage for unauthenticated QR downloads.

The QR code contains only a high-entropy opaque token.  The exported bytes stay
server-side until the link expires or reaches its download limit.  This store is
intentionally process-local: the current Fly deployment uses one worker on one
machine.  A shared TTL store (for example Redis) is required before scaling to
multiple workers or machines.
"""

from __future__ import annotations

import hashlib
import secrets
import threading
import time
from collections import OrderedDict
from dataclasses import dataclass
from typing import Callable, Optional, Tuple


@dataclass(frozen=True)
class ThreatDownload:
    contents: bytes
    file_name: str
    remaining_downloads: int


@dataclass
class _StoredDownload:
    contents: bytes
    file_name: str
    expires_at: float
    remaining_downloads: int


class ThreatDownloadStore:
    """Thread-safe TTL store with bounded entries and download counts."""

    def __init__(
        self,
        *,
        ttl_seconds: int = 600,
        max_downloads: int = 3,
        max_entries: int = 128,
        max_entry_bytes: int = 25 * 1024 * 1024,
        max_total_bytes: int = 64 * 1024 * 1024,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        if (
            ttl_seconds <= 0
            or max_downloads <= 0
            or max_entries <= 0
            or max_entry_bytes <= 0
            or max_total_bytes <= 0
            or max_entry_bytes > max_total_bytes
        ):
            raise ValueError("Store limits must be positive and internally consistent")
        self.ttl_seconds = ttl_seconds
        self.max_downloads = max_downloads
        self.max_entries = max_entries
        self.max_entry_bytes = max_entry_bytes
        self.max_total_bytes = max_total_bytes
        self._clock = clock
        self._entries: OrderedDict[str, _StoredDownload] = OrderedDict()
        self._total_bytes = 0
        self._lock = threading.Lock()

    @staticmethod
    def _key(token: str) -> str:
        return hashlib.sha256(token.encode("ascii")).hexdigest()

    def _remove(self, key: str) -> None:
        entry = self._entries.pop(key, None)
        if entry is not None:
            self._total_bytes -= len(entry.contents)

    def _prune_expired(self, now: float) -> None:
        expired = [
            key for key, entry in self._entries.items()
            if entry.expires_at <= now
        ]
        for key in expired:
            self._remove(key)

    def create(self, contents: bytes, file_name: str) -> str:
        contents = bytes(contents)
        size = len(contents)
        if size > self.max_entry_bytes:
            raise ValueError("Download exceeds the per-link storage limit")
        token = secrets.token_urlsafe(32)
        key = self._key(token)
        now = self._clock()
        with self._lock:
            self._prune_expired(now)
            while self._entries and (
                len(self._entries) >= self.max_entries
                or self._total_bytes + size > self.max_total_bytes
            ):
                oldest_key = next(iter(self._entries))
                self._remove(oldest_key)
            self._entries[key] = _StoredDownload(
                contents=contents,
                file_name=file_name,
                expires_at=now + self.ttl_seconds,
                remaining_downloads=self.max_downloads,
            )
            self._total_bytes += size
        return token

    def take(self, token: str) -> Tuple[Optional[ThreatDownload], str]:
        """Return one download use and a status: ``ok``, ``expired``, or ``missing``."""
        if not isinstance(token, str) or not token or len(token) > 128:
            return None, "missing"
        try:
            key = self._key(token)
        except UnicodeEncodeError:
            return None, "missing"

        now = self._clock()
        with self._lock:
            entry = self._entries.get(key)
            if entry is not None and entry.expires_at <= now:
                self._remove(key)
                self._prune_expired(now)
                return None, "expired"
            self._prune_expired(now)
            if entry is None:
                return None, "missing"

            entry.remaining_downloads -= 1
            result = ThreatDownload(
                contents=entry.contents,
                file_name=entry.file_name,
                remaining_downloads=entry.remaining_downloads,
            )
            if entry.remaining_downloads <= 0:
                self._remove(key)
            return result, "ok"

    def clear(self) -> None:
        """Remove all links. Primarily useful for isolated tests."""
        with self._lock:
            self._entries.clear()
            self._total_bytes = 0
