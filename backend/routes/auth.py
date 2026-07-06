from flask import Blueprint, request, jsonify
from google.oauth2 import id_token
from google.auth.transport import requests
from flask_jwt_extended import create_access_token, jwt_required, get_jwt_identity
import os

from models import db, User

auth_bp = Blueprint('auth', __name__)
GOOGLE_CLIENT_ID = os.environ.get("GOOGLE_CLIENT_ID")

@auth_bp.route('/api/auth/google', methods=['POST'])
def google_auth():
    token = request.json.get('token')

    try:
        # Verify the token with Google's servers
        idinfo = id_token.verify_oauth2_token(token, requests.Request(), GOOGLE_CLIENT_ID)

        email = idinfo['email']
        name = idinfo.get('name', 'Pilot')
        google_id = idinfo['sub']
        picture = idinfo.get('picture')

        user = User.query.filter_by(google_id=google_id).first()
        if not user:
            user = User(email=email, name=name, google_id=google_id, picture=picture)
            db.session.add(user)
            db.session.commit()
        elif picture and user.picture != picture:
            user.picture = picture
            db.session.commit()

        access_token = create_access_token(identity=str(user.id))

        return jsonify({
            "status": "success",
            "access_token": access_token,
            "user": {"id": user.id, "email": user.email, "name": user.name, "picture": user.picture}
        })

    except ValueError:
        # Invalid token
        return jsonify({"status": "error", "message": "Invalid token"}), 401


@auth_bp.route('/api/auth/me', methods=['GET'])
@jwt_required()
def me():
    user = User.query.get(int(get_jwt_identity()))
    if not user:
        return jsonify({"error": "User not found"}), 404

    return jsonify({"id": user.id, "email": user.email, "name": user.name, "picture": user.picture})