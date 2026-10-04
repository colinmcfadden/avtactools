"""Make or reset a **local** account, so an app can sign in against a backend running on your own machine.

A local backend uses its own database (`backend/instance/ezpz.db`, SQLite, unless `DATABASE_URL` is set), which does not hold your
production accounts. Signing in with a production password therefore fails with "Invalid email or password."; so does an account that was
registered here but whose emailed link was never followed (the server answers the same for "no such account" and "not verified yet", so
that an address cannot be probed). This makes the account directly: verified, active, and past the `.mil` / approval gate.

    python dev_user.py you@example.com            # asks for the password
    python dev_user.py you@example.com --admin    # also an administrator

It refuses to run against anything but a local SQLite file, or on a host that looks like a deployment, because it writes a password.
"""
import argparse
import getpass
import os
import sys
import uuid
from datetime import datetime

from werkzeug.security import generate_password_hash

from security_config import is_production


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("email")
    parser.add_argument("--password", help="asked for if left out (keeps it out of your shell history)")
    parser.add_argument("--name", default="Dev Pilot")
    parser.add_argument("--admin", action="store_true", help="make the account an administrator")
    args = parser.parse_args(argv)

    url = (os.environ.get("DATABASE_URL") or "").strip()
    if is_production(os.environ) or (url and not url.startswith("sqlite")):
        sys.exit("Refusing: this is for a local SQLite database only (DATABASE_URL is set to something else, or this looks like a deployment).")

    password = args.password or getpass.getpass("Password for the new account: ")

    # A small app of its own over the same models and the same default database file, so this does not load the 350 MB model the real app loads at start.
    from flask import Flask

    from database_url import database_uri
    from models import LocalCredential, User, db
    from routes.auth import _normalize_email, _password_error, _valid_email

    basedir = os.path.abspath(os.path.dirname(__file__))
    app = Flask(__name__)
    app.config["SQLALCHEMY_DATABASE_URI"] = database_uri(os.environ, "sqlite:///" + os.path.join(basedir, "ezpz.db"))
    app.config["SQLALCHEMY_TRACK_MODIFICATIONS"] = False
    db.init_app(app)

    email = _normalize_email(args.email)
    if not _valid_email(email):
        sys.exit(f"'{args.email}' is not an email address.")
    problem = _password_error(password)
    if problem:
        sys.exit(problem)

    with app.app_context():
        db.create_all()                                                          # a database that has never been run has no tables yet
        user = User.query.filter(db.func.lower(User.email) == email).first()
        made = user is None
        if made:
            user = User(email=email, name=args.name, google_id=f"local:{uuid.uuid4()}")
            db.session.add(user)
            db.session.flush()
        user.is_active = True
        user.access_approved = True
        if args.admin:
            user.role = "admin"
        credential = user.local_credential
        if credential is None:
            credential = LocalCredential(user_id=user.id, password_hash="")
            db.session.add(credential)
        credential.password_hash = generate_password_hash(password, method="scrypt")
        credential.email_verified_at = datetime.utcnow()
        credential.status = "active"
        credential.session_version = (credential.session_version or 0) + 1      # signs out anything issued before
        db.session.commit()
        print(f"{'Made' if made else 'Reset'} {email} (id {user.id}) in {app.config['SQLALCHEMY_DATABASE_URI']}")


if __name__ == "__main__":
    main()
