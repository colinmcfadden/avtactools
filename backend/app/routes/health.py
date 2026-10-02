from flask import Blueprint, redirect, request

from app.version import __version__

health_bp = Blueprint('health', __name__)


@health_bp.route('/')
def health_check():
    # On the admin subdomain (admin.ezpztac.app) the root should land on the
    # admin sign-in; every other host (the API domain, the .fly.dev hostname,
    # and Fly's internal health checks) keeps the JSON status response.
    host = (request.host or '').split(':')[0].lower()
    if host.startswith('admin.'):
        return redirect('/admin/login')
    return {
        "status": "online",
        "service": "AvTacTools Backend",
        "version": __version__
    }
