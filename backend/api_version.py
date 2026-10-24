"""The API version: the contract the clients call, apart from the product's version (`version.py`).

`API_VERSION` is `info.version` of `contracts/openapi.yaml`, and must agree with it
(`contracts/scripts/api_version.py check`, run in CI, fails otherwise). Its major is the
promise to installed apps: within one major nothing they read or send stops working, so a
breaking change to the spec bumps it, and the check fails until it does.

`API_MAJORS_SERVED` is every major this server still answers: the current one, and any older
one kept for apps not yet updated. `/api/config` sends both, and an app that supports none of
the majors served says "Update required" (or, for a server older than the app, that it waits
for the server). Write both as plain literals: the check reads this file as text.
"""

API_VERSION = "1.0.0"

API_MAJORS_SERVED = (1,)
