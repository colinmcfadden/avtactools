"""Notifications: writing the outbox, with the change that causes it (docs/NOTIFICATIONS.md).

A row tells one person that something happened to them: an invitation to a pack or a team, being added to a pack, a
pack finished, a pack handed to them. The routes that make those changes call ``add`` before they commit, so a change
that rolls back notifies nobody, and ``withdraw`` when an invitation is answered or a pack goes. Reading the outbox,
and deciding what of it still holds, is ``routes/notification_routes.py``.

A row holds ids and one name only: the pack's uuid and the invitation's id (columns of their own), and in ``fields``
who did it and a role. The pack's and the team's names are read when the feed is asked for. Never an item, a place or
a grid: a notification shows on a lock screen, and its words are the client's, made from the type and these. Never an
invitation's token either, which is a credential.

Only models here, so pack_support can call this without importing a route.
"""

from datetime import datetime, timedelta

from sqlalchemy import and_, func, or_

from models import MissionPackMember, Notification, TeamMember, User, db

# What each type is grouped under in the person's settings (§5).
GROUPS = {
    'pack_invite': 'invitations',
    'pack_added': 'invitations',
    'team_invite': 'invitations',
    'pack_finished': 'pack_finished',
    'pack_owner': 'pack_owner',
}
SETTINGS = ('invitations', 'pack_finished', 'pack_owner')

# A finish waits this long, and a reopen within it sends nothing (§2.3).
FINISH_DELAY = timedelta(minutes=2)
# An invitation sent again alerts again only once its last alert is this old; before, its notification is updated.
REALERT_AFTER = timedelta(days=1)
# Past this many in an hour, a person's notifications are listed but not sounded.
ALERTS_AN_HOUR = 10
KEPT_FOR = timedelta(days=30)


def now():
    return datetime.utcnow()


def accounts_at(email):
    """The accounts an emailed invitation reaches: sign-in address, or a verified .mil one (as pack_support.addresses)."""
    address = (email or '').strip().casefold()
    if not address:
        return []
    return User.query.filter(or_(
        func.lower(User.email) == address,
        and_(func.lower(User.mil_email) == address, User.mil_verified_at.isnot(None)),
    )).all()


def people_in(pack):
    """Everyone in a pack: its members, and the members of the team it is shared with."""
    ids = {row.user_id for row in db.session.query(MissionPackMember.user_id).filter_by(pack_id=pack.id)}
    if pack.team_id:
        ids |= {row.user_id for row in db.session.query(TeamMember.user_id).filter_by(team_id=pack.team_id)}
    return ids


def add(user_id, type_, *, pack=None, actor=None, invite=None, role=None, delay=None):
    """Tell ``user_id`` of something, in the caller's transaction. Nothing for the person who did it.

    One thing, one notification (``collapse_key``). An invitation sent again is the same thing: its notification is
    updated in place, seen or not, and alerts again only once its last alert is a day old (then the old one is
    withdrawn and a new one made). Anything else under the same key is a new event (added again, handed back,
    finished again): the earlier one is withdrawn and a new one made, which every device alerts for.

    What it keeps is the actor's name and a role. The pack's and the team's names are read as they are when the
    feed is asked for, so nothing here outlives a pack or a team that is deleted.
    """
    if user_id is None or (actor is not None and actor.id == user_id):
        return None
    if invite is not None and invite.id is None:
        db.session.flush()                                       # a new invitation's id, which the notification names
    key = f'{type_}:{invite.id}' if invite is not None else f'{type_}:{pack.uuid}'
    fields = {'actor': {'name': (actor.name or actor.email or '')[:120]} if actor is not None else None, 'role': role}
    moment = now()
    live = Notification.query.filter_by(user_id=user_id, collapse_key=key, withdrawn_at=None)
    earlier = live.order_by(Notification.id.desc()).first()
    if earlier is not None and invite is not None and moment - earlier.created_at < REALERT_AFTER:
        earlier.fields, earlier.updated_at = fields, moment
        return earlier
    live.update({Notification.withdrawn_at: moment}, synchronize_session=False)
    # Alerts in the last hour: delivered, not quiet, and not withdrawn before they were (a finish reopened in time never alerted).
    alerted = Notification.query.filter(
        Notification.user_id == user_id, Notification.quiet.is_(False),
        Notification.deliver_after > moment - timedelta(hours=1), Notification.deliver_after <= moment,
        or_(Notification.withdrawn_at.is_(None), Notification.withdrawn_at >= Notification.deliver_after),
    ).count()
    row = Notification(
        user_id=user_id, type=type_, collapse_key=key, pack_uuid=pack.uuid if pack is not None else None,
        invite_id=invite.id if invite is not None else None, fields=fields, quiet=alerted >= ALERTS_AN_HOUR,
        created_at=moment, updated_at=moment, deliver_after=moment + (delay or timedelta()),
    )
    db.session.add(row)
    return row


def withdraw(*, pack_uuid=None, invite_ids=None, types=None):
    """Take back what is no longer so, in the caller's transaction: every device takes it down at its next look."""
    if pack_uuid is None and invite_ids is None:
        raise ValueError('withdraw what? a pack or invitations')
    query = Notification.query.filter(Notification.withdrawn_at.is_(None))
    if pack_uuid is not None:
        query = query.filter(Notification.pack_uuid == pack_uuid)
    if invite_ids is not None:
        ids = [i for i in invite_ids if i is not None]
        if not ids:
            return
        query = query.filter(Notification.invite_id.in_(ids))
    if types is not None:
        query = query.filter(Notification.type.in_(types))
    query.update({Notification.withdrawn_at: now()}, synchronize_session=False)


def forget_account(user_id):
    """Every notification for an account that is being deleted (pack_support.release_account)."""
    Notification.query.filter_by(user_id=user_id).delete(synchronize_session=False)
