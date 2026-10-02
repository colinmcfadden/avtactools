"""The database URL the app connects with."""


def database_uri(environ, default: str) -> str:
    """DATABASE_URL made ready for SQLAlchemy, or ``default`` when unset.

    The Postgres driver is named explicitly. A bare ``postgresql://`` leaves the
    choice to SQLAlchemy, and 2.1 switched it from psycopg2 to psycopg 3 — which
    this image does not ship. An unpinned rebuild picked up 2.1 and production
    crash-looped on "No module named 'psycopg'".
    """
    url = (environ.get("DATABASE_URL") or "").strip() or default
    # Some providers (Heroku, older Supabase) hand out postgres://.
    if url.startswith("postgres://"):
        url = "postgresql://" + url[len("postgres://"):]
    if url.startswith("postgresql://"):
        url = "postgresql+psycopg2://" + url[len("postgresql://"):]
    return url


def is_postgres(url: str) -> bool:
    return url.startswith("postgresql")
