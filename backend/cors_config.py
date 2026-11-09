"""Which pages may call the API from a browser, and which of them with credentials (the web's refresh cookie)."""

from flask_cors import CORS

from client_header import CLIENT_HEADER
from refresh_cookie import SESSION_HEADER


def cors_origins(environ):
    """``CORS_ORIGINS``, comma separated. An entry with regex characters is a pattern (flask-cors's rule)."""
    return [
        origin.strip()
        for origin in environ.get('CORS_ORIGINS', 'http://localhost:3000').split(',')
        if origin.strip()
    ]


def configure_cors(app, origins):
    CORS(
        app,
        resources={
            # The web's refresh token is a cookie under /api/auth (refresh_cookie), so only those routes answer
            # a request made with credentials, and never to "every origin": with credentials, a wildcard would
            # hand any site's page a session. flask-cors tries the longer pattern first.
            r'/api/auth/*': {
                'origins': [origin for origin in origins if origin != '*'],
                'supports_credentials': True,
            },
            r'/api/*': {'origins': origins},
        },
        # The native apps (and, later, the web) identify themselves with X-EZPZ-Client. The web's LZ surveys
        # save with PATCH and name the revision they were made on with If-Match. X-EZPZ-Session asks for a
        # cookie session, and is what a page on another site cannot send without a preflight.
        allow_headers=['Authorization', 'Content-Type', 'If-Match', CLIENT_HEADER, SESSION_HEADER],
        methods=['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS'],
    )
