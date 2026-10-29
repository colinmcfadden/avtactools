"""The web's refresh token: an HttpOnly cookie, so no script on the page can read it.

The native apps keep their refresh token in the device's encrypted store and send it in the body of
``POST /api/auth/refresh``. A browser has no such store: ``localStorage`` is open to any script on
the page, which is why the web was given no refresh token at all and signed in again every day. A
cookie the browser keeps and sends, and that JavaScript cannot see, is the store a browser does have.

Design, and why:

* **Asked for, never assumed.** Only a sign-in that sends ``X-EZPZ-Session: cookie`` starts a cookie
  session, so an older web bundle, a script with curl and the native apps behave as they always did.
* **The same family rules as the apps** (``refresh_tokens``): rotated on every use, a spent token
  forgiven for 30 seconds and read as a copy after that, 30 days from the last refresh. A web family
  is marked by its client (``web``), and a token is refreshed only the way its family was issued:
  a web token never comes back in a response body, a native one never by cookie.
* **The header is the CSRF guard.** The cookie is ``SameSite=None``, because the API and the web are
  on different sites (the web on Vercel, the API behind Cloudflare), and so the browser sends it on a
  cross-site request too. A page elsewhere cannot add a custom header without a CORS preflight, which
  is refused for any origin not in ``CORS_ORIGINS``; a form post cannot add one at all.
* **Partitioned** (CHIPS): the cookie is kept for the site the web app was opened on, so a browser
  that blocks third-party cookies may still keep it, and a page on another site (another
  ``*.vercel.app`` preview) is never sent it. A browser that refuses it outright (Safari, while the
  API is on another site) gets no cookie; the web then keeps the sign-in's 24-hour token as it used to.
* **Only under ``/api/auth``**, so it is never sent with any other request.
"""

REFRESH_COOKIE = 'ezpz_refresh'
REFRESH_COOKIE_PATH = '/api/auth'
SESSION_HEADER = 'X-EZPZ-Session'
SESSION_HEADER_VALUE = 'cookie'

# The `client` a web family is stored under, shown in the account's list of signed-in devices.
WEB_CLIENT = 'web'


def requested(request):
    """Whether this request asks for, or carries, a cookie session (the CSRF guard as well)."""
    return (request.headers.get(SESSION_HEADER) or '').strip().lower() == SESSION_HEADER_VALUE


def presented(request):
    """The refresh token in the request's cookie, or None."""
    raw = request.cookies.get(REFRESH_COOKIE)
    return raw if isinstance(raw, str) and raw else None


def is_web_family(row):
    return row is not None and row.client == WEB_CLIENT


def set_on(response, raw_token, lifetime):
    response.set_cookie(
        REFRESH_COOKIE,
        raw_token,
        max_age=int(lifetime.total_seconds()),
        path=REFRESH_COOKIE_PATH,
        secure=True,
        httponly=True,
        samesite='None',
        partitioned=True,
    )
    return response


def clear_on(response):
    response.delete_cookie(
        REFRESH_COOKIE,
        path=REFRESH_COOKIE_PATH,
        secure=True,
        httponly=True,
        samesite='None',
        partitioned=True,
    )
    return response
