"""Add columns a model gained after its table was already created.

``db.create_all()`` creates missing *tables* and nothing else — it will never
alter one that already exists. A table created by an earlier version of a model
therefore keeps its old shape forever, and the first query naming a newer
column fails with "no such column".

The older tables here handle that with a hand-maintained list of idempotent
ALTERs. This does the same job by diffing the model against the live schema, so
adding a column to the model is all that's needed.

Deliberately conservative:

* Columns are always added **nullable**, even when the model says NOT NULL.
  Several databases refuse to add a NOT NULL column to a table with existing
  rows, and the model's own default still applies to every new insert.
* A scalar default is emitted as ``DEFAULT`` so existing rows are backfilled
  rather than left NULL. Callable defaults (``datetime.utcnow``) are skipped —
  they're per-row and can't be expressed in DDL.
* Columns present in the table but absent from the model are left alone.
  Dropping data is never worth doing automatically.
"""

from sqlalchemy import inspect as sa_inspect, text


def _default_literal(column):
    """SQL literal for a column's scalar default, or None if there isn't one."""
    default = getattr(column, "default", None)
    if default is None or getattr(default, "is_callable", False):
        return None
    value = getattr(default, "arg", None)
    if value is None or callable(value):
        return None
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, str):
        escaped = value.replace("'", "''")
        return f"'{escaped}'"
    return None


def sync_table_columns(db, model):
    """Add every model column missing from ``model``'s existing table.

    Returns the list of column names added (empty when already in sync, or when
    the table doesn't exist yet — create_all handles that case correctly).
    """
    table = model.__tablename__
    try:
        inspector = sa_inspect(db.engine)
        if not inspector.has_table(table):
            return []
        existing = {column["name"] for column in inspector.get_columns(table)}
    except Exception:  # noqa: BLE001 — never let schema inspection block boot
        return []

    dialect = db.engine.dialect
    added = []
    for column in model.__table__.columns:
        if column.name in existing:
            continue
        try:
            column_type = column.type.compile(dialect)
        except Exception:  # noqa: BLE001 — exotic type we can't render as DDL
            continue

        ddl = f'ALTER TABLE "{table}" ADD COLUMN "{column.name}" {column_type}'
        literal = _default_literal(column)
        if literal is not None:
            ddl += f" DEFAULT {literal}"

        try:
            db.session.execute(text(ddl))
            db.session.commit()
            added.append(column.name)
        except Exception:  # noqa: BLE001 — raced with another worker, or unsupported
            db.session.rollback()

    return added
