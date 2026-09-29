"""Security-sensitive configuration validation kept independently testable."""

import ipaddress

# The header each edge sets to the real caller's address. A header like this is
# attacker-controlled unless something guarantees the request actually came
# through that proxy, so one is read only when the deployment says which proxy
# is in front of it.
TRUSTED_PROXY_HEADERS = {
    'fly': 'Fly-Client-IP',
    'cloudflare': 'CF-Connecting-IP',
}


def trusted_proxy_header(environ):
    """Name of the client-IP header to believe, or None if nothing is in front.

    Fly announces itself through ``FLY_APP_NAME``, so it needs no configuration.
    Any other deployment names its edge with ``TRUSTED_PROXY`` — behind a
    Cloudflare tunnel, every request arrives from the tunnel's own address, so
    without this the rate limiter buckets the whole internet together and the
    admin audit log records one meaningless IP.
    """
    name = (environ.get('TRUSTED_PROXY') or '').strip().lower()
    if not name and environ.get('FLY_APP_NAME'):
        name = 'fly'
    return TRUSTED_PROXY_HEADERS.get(name)


def resolve_client_ip(request, environ):
    """The caller's address: the trusted edge's header, else the WSGI peer.

    The header is parsed as an address rather than passed through, so a forged
    or malformed value falls back to the peer instead of poisoning a rate-limit
    bucket with arbitrary text.
    """
    peer = request.remote_addr or 'unknown'
    header = trusted_proxy_header(environ)
    if not header:
        return peer
    try:
        return str(ipaddress.ip_address((request.headers.get(header) or '').strip()))
    except ValueError:
        return peer


def session_cookie_secure(environ):
    """Whether the admin session cookie is restricted to HTTPS.

    An explicit ``SESSION_COOKIE_SECURE`` wins. Otherwise it follows whether an
    edge is declared: anything reached through Fly or Cloudflare is served over
    HTTPS, while a bare local run is not — and a Secure cookie would simply
    never be sent, breaking admin sign-in on http://localhost.
    """
    setting = (environ.get('SESSION_COOKIE_SECURE') or '').strip().lower()
    if setting in ('1', 'true', 'yes', 'on'):
        return True
    if setting in ('0', 'false', 'no', 'off'):
        return False
    return bool(trusted_proxy_header(environ))


PRODUCTION_SIGNALS = 'FLY_APP_NAME, TRUSTED_PROXY or APP_ENV=production'


def is_production(environ):
    """Whether this process is a deployment, so startup checks must hold.

    These checks were keyed to ``FLY_APP_NAME``, so the self-hosted deployment
    behind Cloudflare skipped them and silently signed every session with the
    public development secret. Any declared edge now counts — a bare local run
    never sets ``TRUSTED_PROXY`` — and ``APP_ENV=production`` covers a host
    with neither.

    There is deliberately no switch back to the development fallback: the only
    thing it would allow is the forgeable secret on a deployed host. To run
    locally with ``TRUSTED_PROXY`` set, give it a JWT secret and email settings.
    """
    if environ.get('FLY_APP_NAME') or (environ.get('TRUSTED_PROXY') or '').strip():
        return True
    return (environ.get('APP_ENV') or '').strip().lower() in ('production', 'prod')


def resolve_jwt_secret(environ):
    secret = environ.get('JWT_SECRET_KEY')
    if is_production(environ) and (not secret or len(secret) < 32):
        raise RuntimeError(
            'JWT_SECRET_KEY must be configured in production '
            f'({PRODUCTION_SIGNALS} is set) and contain at least 32 characters'
        )
    return secret or 'dev-secret-change-me'


def validate_email_configuration(environ):
    """Refuse a deployment that cannot deliver account activation mail."""

    if not is_production(environ):
        return

    api_key = (environ.get('RESEND_API_KEY') or '').strip()
    sender = (environ.get('EMAIL_FROM') or '').strip().lower()
    if not api_key:
        raise RuntimeError(
            'RESEND_API_KEY must be configured in production '
            f'({PRODUCTION_SIGNALS} is set) for account email delivery'
        )
    if not sender or 'onboarding@resend.dev' in sender:
        raise RuntimeError(
            'EMAIL_FROM must use a verified Resend sending domain in production '
            f'({PRODUCTION_SIGNALS} is set)'
        )
