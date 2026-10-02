"""Which app made a request: the ``X-EZPZ-Client`` header.

The native apps send ``X-EZPZ-Client: android/1.4.0 (212)`` (platform, version,
build number) on every request, so the owner can see which app versions are in
the field before changing an endpoint. Store builds stay on devices for months;
this is how a breaking change is judged safe.

The header is client-supplied text that ends up in the admin dashboard, so only
one exact shape is accepted and everything else is dropped rather than stored.
"""

import re

CLIENT_HEADER = "X-EZPZ-Client"

# Long enough for any real value; anything longer is not one.
_MAX_LENGTH = 64

_PATTERN = re.compile(
    r"^(?P<platform>(?i:android|ios|web))"
    r"/(?P<version>\d{1,4}\.\d{1,4}\.\d{1,4}(?:-[0-9A-Za-z.]{1,20})?)"
    r"(?: \((?P<build>\d{1,9})\))?$"
)


def parse_client(raw):
    """``{'platform', 'version', 'build'}`` for a well-formed header, else None.

    ``build`` is None when the client sent no build number. The platform is
    case-insensitive on the way in and always lower case on the way out.
    """
    if not isinstance(raw, str) or not raw or len(raw) > _MAX_LENGTH:
        return None
    match = _PATTERN.match(raw.strip())
    if not match:
        return None
    build = match.group("build")
    return {
        "platform": match.group("platform").lower(),
        "version": match.group("version"),
        "build": int(build) if build is not None else None,
    }


def client_label(raw):
    """The normalised header, ``android/1.4.0 (212)``, or None when it is not well formed."""
    parsed = parse_client(raw)
    if parsed is None:
        return None
    label = f"{parsed['platform']}/{parsed['version']}"
    if parsed["build"] is not None:
        label += f" ({parsed['build']})"
    return label


def client_from_request(request):
    """The label for the request's header, for storing on a login event."""
    return client_label(request.headers.get(CLIENT_HEADER))
