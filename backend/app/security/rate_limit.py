"""Small single-process rate limiter for authentication endpoints.

The current Fly deployment runs one Gunicorn worker on one machine, so this
provides useful prototype protection without another service. It is deliberately
not a distributed security boundary: before scaling to multiple workers or
machines, replace it with a Redis-backed limiter or an edge/gateway policy.
"""

from collections import defaultdict, deque
from threading import Lock
import time


_buckets = defaultdict(deque)
_lock = Lock()


def check_rate_limits(rules):
    """Consume a request across several buckets.

    ``rules`` is an iterable of ``(scope, identifier, limit, window_seconds)``.
    Returns the number of seconds to retry after, or zero when allowed.
    """

    now = time.monotonic()
    normalized_rules = [rule for rule in rules if rule[1]]
    with _lock:
        blocked_for = 0
        buckets = []
        for scope, identifier, limit, window_seconds in normalized_rules:
            key = (scope, str(identifier))
            bucket = _buckets[key]
            cutoff = now - window_seconds
            while bucket and bucket[0] <= cutoff:
                bucket.popleft()
            buckets.append((bucket, limit, window_seconds))
            if len(bucket) >= limit:
                blocked_for = max(
                    blocked_for,
                    max(1, int(window_seconds - (now - bucket[0])) + 1),
                )

        if blocked_for:
            return blocked_for

        for bucket, _limit, _window_seconds in buckets:
            bucket.append(now)
    return 0


def clear_rate_limits():
    """Test helper; production code should never need to clear live buckets."""

    with _lock:
        _buckets.clear()
