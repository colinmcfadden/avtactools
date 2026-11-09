"""Who may read and who may edit a book of LZ surveys (docs/LZ_SURVEYS.md §7; the owner's decision of 2026-10-26).

- **Site admins and the super-admin** (``entitlements.is_admin``) read and edit every book, archived or not, and make a book for any
  team or for none.
- **Everyone else needs ``lz_surveys``** (ticked by an admin): it is the survey books' key, as it was the one book's.
- With it, **a team's owner and admins** edit their team's books (make, measure, publish and archive its surveys, and its own
  details) and make books for their team; they cannot give a book to another team or to none.
- With it, **a team's members** read the published pages of their team's books, and **everyone** reads those of a book that is for
  no team. A draft is only ever an editor's.

A book's team is kept as its id with no foreign key: deleting a team archives its books (``pack_support.delete_team``), and a book
whose team is gone has no members, so nobody but a site admin reaches it. It never falls back to being everyone's.
"""

from __future__ import annotations

from entitlements import has_feature, is_admin
from models import Team, TeamMember, db

EDIT_ROLES = ("owner", "admin")


def has_key(user):
    return user is not None and (is_admin(user) or has_feature(user, "lz_surveys"))


def team_role(user, team_id):
    """The user's role in a team (owner, admin, member), or None."""
    if user is None or team_id is None:
        return None
    member = TeamMember.query.filter_by(team_id=team_id, user_id=user.id).first()
    return member.role if member else None


def can_edit(user, book):
    if user is None or book is None:
        return False
    if is_admin(user):
        return True
    if not has_feature(user, "lz_surveys") or book.team_id is None or book.archived_at is not None:
        return False
    return team_role(user, book.team_id) in EDIT_ROLES


def can_read(user, book):
    """Whether the user reads the book's published pages (an editor reads its drafts too: ``can_edit``)."""
    if user is None or book is None:
        return False
    if is_admin(user):
        return True
    if not has_feature(user, "lz_surveys") or book.archived_at is not None:
        return False
    return book.team_id is None or team_role(user, book.team_id) is not None


def can_make_book_for(user, team_id):
    """Whether the user may make a book for a team (None: a book for no team, read by everyone with the key)."""
    if user is None:
        return False
    if is_admin(user):
        return team_id is None or db.session.get(Team, team_id) is not None
    return team_id is not None and has_feature(user, "lz_surveys") and team_role(user, team_id) in EDIT_ROLES


def teams_for_books(user):
    """The teams the user may make or give a book to: every team for a site admin, else those they own or run."""
    if user is None:
        return []
    if is_admin(user):
        return Team.query.order_by(Team.name).all()
    if not has_feature(user, "lz_surveys"):
        return []
    ids = [m.team_id for m in TeamMember.query.filter(TeamMember.user_id == user.id, TeamMember.role.in_(EDIT_ROLES)).all()]
    return Team.query.filter(Team.id.in_(ids)).order_by(Team.name).all() if ids else []
