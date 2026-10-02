from datetime import datetime

from app.extensions import db


class SavedRoute(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.Integer, db.ForeignKey('user.id'), nullable=False)
    name = db.Column(db.String(100), nullable=False)

    # "sketch" = geometry JSON only; "mission" = also carries the full .msnx
    # bytes (serialized from the edited in-memory state at save time).
    kind = db.Column(db.String(20), nullable=False, default='sketch')
    route_data = db.Column(db.JSON, nullable=False)
    msnx_file = db.Column(db.LargeBinary, nullable=True)
    file_name = db.Column(db.String(255), nullable=True)

    created_at = db.Column(db.DateTime, default=datetime.utcnow)
    updated_at = db.Column(db.DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)


class SavedPointSet(db.Model):
    """A named set of local points (imported from an .LPS file) for map display."""
    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.Integer, db.ForeignKey('user.id'), nullable=False)
    name = db.Column(db.String(100), nullable=False)

    # [{name, description, group, icon, elevationM, lat, lon}, ...]
    points_data = db.Column(db.JSON, nullable=False)

    created_at = db.Column(db.DateTime, default=datetime.utcnow)
    updated_at = db.Column(db.DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)


class SavedLZ(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.Integer, db.ForeignKey('user.id'), nullable=False)
    name = db.Column(db.String(100), nullable=False)

    lz_data = db.Column(db.JSON, nullable=False)

    created_at = db.Column(db.DateTime, default=datetime.utcnow)
    updated_at = db.Column(db.DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
