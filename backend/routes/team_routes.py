"""Teams, finding people, and answering invitations (to a pack or a team).

Every user here is a verified Army aviator, so there is no open directory: name
search finds only people who share a team with the caller, and anyone else is
reached by an emailed invitation. Everything needs the ``mission_packs``
entitlement. See docs/MISSION_PACKS.md.
"""

from flask import Blueprint, request
from flask_jwt_extended import get_jwt_identity, jwt_required
from sqlalchemy import func, or_, select

import pack_support as packs
from email_service import send_team_invite_email
from entitlements import account_active, require_feature
from models import MissionPack, MissionPackInvite, MissionPackMember, Team, TeamMember, User, db
from routes.auth import _normalize_email, _valid_email
from routes.pack_routes import _body, _error, _me, _rate_limited, _reply, clean_name

team_bp = Blueprint('teams', __name__)

SEARCH_LIMIT = 20


def _team_for(team_id, user_id, need=None):
    """``(team, role, None)``, or ``(None, None, response)``. A team the caller is not in is a 404."""
    team = db.session.get(Team, team_id)
    role = packs.team_role_of(team_id, user_id) if team is not None else None
    if role is None:
        return None, None, _error('Not found', 404, 'team_not_found')
    if need == 'manage' and role not in packs.TEAM_MANAGERS:
        return None, None, _error("Only the team's owner or an admin can do that.", 403, 'team_managers_only')
    if need == 'owner' and role != 'owner':
        return None, None, _error("Only the team's owner can do that.", 403, 'owner_only')
    return team, role, None


def _team_summary(team, role):
    return {
        'id': team.id,
        'name': team.name,
        'role': role,
        'member_count': TeamMember.query.filter_by(team_id=team.id).count(),
        'created_at': packs.iso(team.created_at),
    }


def _team_full(team, role):
    members = (TeamMember.query.filter_by(team_id=team.id)
               .order_by(TeamMember.joined_at, TeamMember.id).all())
    users = {u.id: u for u in User.query.filter(User.id.in_([m.user_id for m in members])).all()} if members else {}
    return {
        **_team_summary(team, role),
        'members': [{
            'user_id': m.user_id,
            'name': users[m.user_id].name if m.user_id in users else '',
            'email': users[m.user_id].email if m.user_id in users else '',
            'role': m.role,
            'joined_at': packs.iso(m.joined_at),
        } for m in members],
    }


# -- Teams ----------------------------------------------------------------------

@team_bp.route('/api/teams', methods=['GET'])
@jwt_required()
@require_feature('mission_packs')
def list_teams():
    user_id = int(get_jwt_identity())
    memberships = TeamMember.query.filter_by(user_id=user_id).all()
    teams = {t.id: t for t in Team.query.filter(Team.id.in_([m.team_id for m in memberships])).all()} if memberships else {}
    found = [_team_summary(teams[m.team_id], m.role) for m in memberships if m.team_id in teams]
    return _reply({'teams': sorted(found, key=lambda t: t['name'].casefold())})


@team_bp.route('/api/teams', methods=['POST'])
@jwt_required()
@require_feature('mission_packs')
def create_team():
    me = _me()
    name = clean_name(_body().get('name'))
    if name is None:
        return _error('Give the team a name of 1 to 100 characters.', 400, 'invalid_name')
    team = Team(name=name, created_by=me.id)
    db.session.add(team)
    db.session.flush()
    db.session.add(TeamMember(team_id=team.id, user_id=me.id, role='owner'))
    db.session.commit()
    return _reply(_team_full(team, 'owner'), 201)


@team_bp.route('/api/teams/<int:team_id>', methods=['GET'])
@jwt_required()
@require_feature('mission_packs')
def get_team(team_id):
    team, role, refused = _team_for(team_id, int(get_jwt_identity()))
    if refused:
        return refused
    return _reply(_team_full(team, role))


@team_bp.route('/api/teams/<int:team_id>', methods=['PUT'])
@jwt_required()
@require_feature('mission_packs')
def rename_team(team_id):
    team, role, refused = _team_for(team_id, int(get_jwt_identity()), need='manage')
    if refused:
        return refused
    name = clean_name(_body().get('name'))
    if name is None:
        return _error('Give the team a name of 1 to 100 characters.', 400, 'invalid_name')
    team.name = name
    db.session.commit()
    return _reply(_team_full(team, role))


@team_bp.route('/api/teams/<int:team_id>', methods=['DELETE'])
@jwt_required()
@require_feature('mission_packs')
def delete_team(team_id):
    me = _me()
    team, _role, refused = _team_for(team_id, me.id, need='owner')
    if refused:
        return refused
    packs.delete_team(team, me)
    db.session.commit()
    return _reply({'status': 'deleted'})


@team_bp.route('/api/teams/<int:team_id>/members/<int:user_id>', methods=['PUT'])
@jwt_required()
@require_feature('mission_packs')
def change_team_member(team_id, user_id):
    """Only the owner changes roles. Making someone the owner hands the team over; the old owner becomes an admin."""
    me = _me()
    team, _role, refused = _team_for(team_id, me.id, need='owner')
    if refused:
        return refused
    role = _body().get('role')
    if role not in packs.TEAM_ROLES:
        return _error('role must be owner, admin or member.', 400, 'invalid_role')
    member = TeamMember.query.filter_by(team_id=team.id, user_id=user_id).first()
    if member is None:
        return _error('They are not in this team.', 404, 'member_not_found')
    if member.user_id == me.id and role != 'owner':
        return _error('Make someone else the owner first.', 409, 'owner_must_transfer')
    if role == 'owner' and member.user_id != me.id:
        TeamMember.query.filter_by(team_id=team.id, user_id=me.id).update({'role': 'admin'})
    member.role = role
    db.session.commit()
    return _reply(_team_full(team, packs.team_role_of(team.id, me.id)))


@team_bp.route('/api/teams/<int:team_id>/members/<int:user_id>', methods=['DELETE'])
@jwt_required()
@require_feature('mission_packs')
def remove_team_member(team_id, user_id):
    """Someone leaves, or the owner or an admin removes them (an admin removes members only)."""
    me = _me()
    team, role, refused = _team_for(team_id, me.id, need=None if user_id == me.id else 'manage')
    if refused:
        return refused
    member = TeamMember.query.filter_by(team_id=team.id, user_id=user_id).first()
    if member is None:
        return _error('They are not in this team.', 404, 'member_not_found')
    if member.role == 'owner':
        return _error('Make someone else the owner first.', 409, 'owner_must_transfer')
    if user_id != me.id and role == 'admin' and member.role != 'member':
        return _error("Only the team's owner can remove an admin.", 403, 'owner_only')
    db.session.delete(member)
    db.session.commit()
    return _reply({'status': 'removed'})


# -- Team invitations -------------------------------------------------------------

@team_bp.route('/api/teams/<int:team_id>/invites', methods=['GET'])
@jwt_required()
@require_feature('mission_packs')
def list_team_invites(team_id):
    team, _role, refused = _team_for(team_id, int(get_jwt_identity()), need='manage')
    if refused:
        return refused
    invites = (MissionPackInvite.query.filter_by(team_id=team.id, status='pending')
               .order_by(MissionPackInvite.created_at.desc()).all())
    return _reply({'invites': [packs.invite_body(i) for i in invites]})


@team_bp.route('/api/teams/<int:team_id>/invites', methods=['POST'])
@jwt_required()
@require_feature('mission_packs')
def invite_to_team(team_id):
    """By email, or with no email a single-use link, whose token is shown this once and never again."""
    me = _me()
    team, role, refused = _team_for(team_id, me.id, need='manage')
    if refused:
        return refused
    body = _body()
    invite_role = body.get('role', 'member')
    if invite_role not in ('admin', 'member') or (invite_role == 'admin' and role != 'owner'):
        return _error('role must be member (or admin, which only the owner can give).', 400, 'invalid_role')
    email = None
    if body.get('email') is not None:
        email = _normalize_email(body.get('email'))
        if not _valid_email(email):
            return _error('Enter a valid email address.', 400, 'invalid_email')
        team_ids = select(TeamMember.user_id).where(TeamMember.team_id == team.id)
        if User.query.filter(func.lower(User.email) == email, User.id.in_(team_ids)).first():
            return _error('They are already in this team.', 409, 'already_member')
    limited = _rate_limited('team_invite:user', me.id, 30, 3600)
    if limited:
        return limited

    raw, hashed = packs.new_token()
    invite = MissionPackInvite(team_id=team.id, email=email, token_hash=hashed, invited_by=me.id, role=invite_role,
                               status='pending', expires_at=packs.now() + packs.INVITE_LIFETIME)
    db.session.add(invite)
    db.session.commit()
    body = {'invite': packs.invite_body(invite)}
    if email:
        try:
            body['email_sent'] = bool(send_team_invite_email(email, me.name, team.name, raw))
        except Exception:  # noqa: BLE001 — the invite stands; it can be sent again
            body['email_sent'] = False
    else:
        body['token'] = raw
    return _reply(body, 201)


@team_bp.route('/api/teams/<int:team_id>/invites/<int:invite_id>', methods=['DELETE'])
@jwt_required()
@require_feature('mission_packs')
def revoke_team_invite(team_id, invite_id):
    team, _role, refused = _team_for(team_id, int(get_jwt_identity()), need='manage')
    if refused:
        return refused
    invite = MissionPackInvite.query.filter_by(id=invite_id, team_id=team.id, status='pending').first()
    if invite is None:
        return _error('Not found', 404, 'invite_not_found')
    invite.status = 'revoked'
    db.session.commit()
    return _reply({'invite': packs.invite_body(invite)})


# -- Finding people ---------------------------------------------------------------

def _like(text):
    escaped = text.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_')
    return f'%{escaped}%'


@team_bp.route('/api/users/search', methods=['GET'])
@jwt_required()
@require_feature('mission_packs')
def search_users():
    """People who share a team with the caller, by name or sign-in email. Never a .mil address they did not sign in with."""
    me = _me()
    q = (request.args.get('q') or '').strip()
    if len(q) < 2 or len(q) > 100:
        return _error('Search for 2 to 100 characters.', 400, 'invalid_query')
    mine = select(TeamMember.team_id).where(TeamMember.user_id == me.id)
    teammates = select(TeamMember.user_id).where(TeamMember.team_id.in_(mine))
    like = _like(q)
    found = (User.query.filter(User.id.in_(teammates), User.id != me.id,
                               or_(User.name.ilike(like, escape='\\'), User.email.ilike(like, escape='\\')))
             .order_by(User.name, User.id).limit(SEARCH_LIMIT).all())
    return _reply({'users': [{'id': u.id, 'name': u.name, 'email': u.email} for u in found if account_active(u)]})


# -- Answering invitations ----------------------------------------------------------

@team_bp.route('/api/invites', methods=['GET'])
@jwt_required()
@require_feature('mission_packs')
def my_invites():
    """Invitations waiting for the caller: sent to their sign-in address or their verified .mil one."""
    me = _me()
    waiting = (MissionPackInvite.query.filter(MissionPackInvite.email.in_(packs.addresses(me)),
                                              MissionPackInvite.status == 'pending',
                                              MissionPackInvite.expires_at > packs.now())
               .order_by(MissionPackInvite.created_at.desc()).all())
    bodies = [packs.invite_body(i) for i in waiting]
    return _reply({'invites': [b for b in bodies if b['pack'] or b['team']]})


def _accept(invite, me):
    if invite.status != 'pending':
        return _error('That invitation has already been answered or withdrawn.', 410, 'invite_gone')
    if invite.expires_at <= packs.now():
        return _error('That invitation has expired. Ask for a new one.', 410, 'invite_expired')

    joined_pack = joined_team = None
    if invite.pack_id:
        pack = db.session.get(MissionPack, invite.pack_id)
        if pack is None or pack.deleted_at is not None:
            return _error('That pack no longer exists.', 410, 'invite_gone')
        packs.lock(pack)
        if MissionPackMember.query.filter_by(pack_id=pack.id, user_id=me.id).first() is None:
            db.session.add(MissionPackMember(pack_id=pack.id, user_id=me.id, role=invite.role,
                                             added_by=invite.invited_by))
            packs.append(pack, me, {'type': 'member.join', 'user_id': me.id, 'name': me.name, 'role': invite.role},
                         f'{packs.actor_name(me)} joined the pack as {"an editor" if invite.role == "editor" else "a viewer"}.')
        joined_pack = pack
    else:
        team = db.session.get(Team, invite.team_id)
        if team is None:
            return _error('That team no longer exists.', 410, 'invite_gone')
        if TeamMember.query.filter_by(team_id=team.id, user_id=me.id).first() is None:
            db.session.add(TeamMember(team_id=team.id, user_id=me.id, role=invite.role))
        joined_team = team
    invite.status, invite.accepted_at, invite.accepted_by = 'accepted', packs.now(), me.id
    db.session.commit()
    return _reply({
        'invite': packs.invite_body(invite),
        'pack': packs.pack_summary(joined_pack, packs.role_of(joined_pack, me.id), me.id) if joined_pack else None,
        'team': _team_summary(joined_team, packs.team_role_of(joined_team.id, me.id)) if joined_team else None,
    })


def _addressed_to_me(invite_id, me):
    invite = db.session.get(MissionPackInvite, invite_id)
    if invite is None or invite.email is None or invite.email not in packs.addresses(me):
        return None
    return invite


@team_bp.route('/api/invites/<int:invite_id>/accept', methods=['POST'])
@jwt_required()
@require_feature('mission_packs')
def accept_invite(invite_id):
    me = _me()
    invite = _addressed_to_me(invite_id, me)
    if invite is None:
        return _error('Not found', 404, 'invite_not_found')
    return _accept(invite, me)


@team_bp.route('/api/invites/<int:invite_id>/decline', methods=['POST'])
@jwt_required()
@require_feature('mission_packs')
def decline_invite(invite_id):
    me = _me()
    invite = _addressed_to_me(invite_id, me)
    if invite is None:
        return _error('Not found', 404, 'invite_not_found')
    if invite.status != 'pending':
        return _error('That invitation has already been answered or withdrawn.', 410, 'invite_gone')
    invite.status = 'declined'
    db.session.commit()
    return _reply({'invite': packs.invite_body(invite)})


@team_bp.route('/api/invites/accept', methods=['POST'])
@jwt_required()
@require_feature('mission_packs')
def accept_by_token():
    """Accept from the emailed or shared link, whichever address the person signed in with."""
    me = _me()
    raw = _body().get('token')
    invite = (MissionPackInvite.query.filter_by(token_hash=packs.token_hash(raw)).first()
              if isinstance(raw, str) and 0 < len(raw) <= 200 else None)
    if invite is None:
        return _error('That invitation link is not valid.', 404, 'invite_not_found')
    return _accept(invite, me)
