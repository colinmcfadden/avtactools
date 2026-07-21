"""Security-sensitive configuration validation kept independently testable."""


def resolve_jwt_secret(environ):
    secret = environ.get('JWT_SECRET_KEY')
    if environ.get('FLY_APP_NAME') and (not secret or len(secret) < 32):
        raise RuntimeError(
            'JWT_SECRET_KEY must be configured on Fly and contain at least 32 characters'
        )
    return secret or 'dev-secret-change-me'


def validate_email_configuration(environ):
    """Refuse a Fly deployment that cannot deliver account activation mail."""

    if not environ.get('FLY_APP_NAME'):
        return

    api_key = (environ.get('RESEND_API_KEY') or '').strip()
    sender = (environ.get('EMAIL_FROM') or '').strip().lower()
    if not api_key:
        raise RuntimeError(
            'RESEND_API_KEY must be configured on Fly for account email delivery'
        )
    if not sender or 'onboarding@resend.dev' in sender:
        raise RuntimeError(
            'EMAIL_FROM must use a verified Resend sending domain on Fly'
        )
