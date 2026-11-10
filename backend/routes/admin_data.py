"""The admin's Data tab (docs/DATA_ADMIN_AND_PREBUILT_3D_PLAN.md §10): a map of the terrain and LiDAR the server holds,
DEMs downloaded from USGS into the terrain folder, and the FAA's VFR charts built into tiles (docs/VFR_CHARTS.md §5).

On the admin blueprint, behind its session and CSRF; nothing here takes a JWT, so no app and no user who is not an
admin can reach a place. ``DATA_ADMIN`` decides what it offers (``data_admin``). Every JSON answer is
``Cache-Control: private, no-store``.
"""

import threading
from datetime import date, datetime, timezone
from xml.etree.ElementTree import ParseError

from flask import abort, current_app, jsonify, render_template, request

import data_admin
from data_admin import jobs, layers, usgs, validate, volumes
from charts import auto as chart_auto, catalog as chart_catalog, faa, job as chart_job, locks as chart_locks, \
    state as chart_state
from models import db, DataJob
from routes.admin_routes import admin_bp, admin_required, _check_csrf

RECENT_JOBS = 10
_creating = jobs.CREATING      # one job at a time (data_admin.jobs)
BUSY = "Another job is under way. Wait for it, or cancel it."


def _json(body, status=200):
    response = jsonify(body)
    response.status_code = status
    response.headers["Cache-Control"] = "private, no-store"
    return response


def _refuse(message, status=400):
    return _json({"ok": False, "error": message}, status)


def _readable():
    if not data_admin.can_read():
        abort(404)


def _writable():
    _readable()
    if not data_admin.can_write():
        return _refuse("Writes are off on this server (DATA_ADMIN is not write).", 403)
    return None


def _job_dict(job):
    return {
        "id": job.id, "kind": job.kind, "product": job.product, "state": job.state, "error": job.error, "replace": job.replace,
        "build": job.build, "stage": job.stage, "work_done": job.work_done, "work_total": job.work_total,
        "files_total": job.files_total, "files_done": job.files_done,
        "bytes_total": job.bytes_total, "bytes_done": job.bytes_done,
        "requested_by": job.requested_by_name, "cancel_requested": job.cancel_requested,
        "created_at": job.created_at.isoformat() + "Z" if job.created_at else None,
        "finished_at": job.finished_at.isoformat() + "Z" if job.finished_at else None,
        "files": [{key: entry.get(key) for key in ("name", "size", "done", "bounds", "state", "reasons", "notes",
                                                    "held")} for entry in job.files or []],
    }


def _resume():
    jobs.RUNNER.resume(current_app._get_current_object())


@admin_bp.route('/data')
@admin_required
def data():
    _readable()
    _resume()
    return render_template('admin/data.html', nav_section='data', mode=data_admin.mode(),
                           products={key: value["label"] for key, value in usgs.PRODUCTS.items()})


@admin_bp.route('/data/layers/<name>.json')
@admin_required
def data_layer(name):
    _readable()
    fresh = request.args.get('fresh') == '1'
    if name == 'dems':
        return _json(layers.dems())
    if name == 'stream':
        return _json(layers.stream_cache(fresh=fresh))
    if name == 'tilesets':
        return _json(layers.tilesets(fresh=fresh))
    if name == 'lidar':
        return _json(layers.downloaded_lidar())
    if name == 'aws':
        return _json(layers.aws_coverage())
    if name in layers.VFR_LAYERS:
        # The live build's charts, or the one chosen in the Charts card, which state.json must list (§5.6).
        try:
            return _json(layers.vfr_charts(name, request.args.get('build') or None))
        except layers.BuildNotListed:
            return _refuse("That build is not in the chart folder's state.json.", 404)
    abort(404)


@admin_bp.route('/data/volumes.json')
@admin_required
def data_volumes():
    _readable()
    places = [{"label": place.label, "setting": place.setting, **volumes.usage(place.path)}
              for place in volumes.places()]
    for place in places:
        place.pop("path")   # the panel names a place by its setting; the server's own paths stay on it
    return _json({"mode": data_admin.mode(), "places": places,
                  "terrain_generations": layers.generations(fresh=request.args.get('fresh') == '1'),
                  "dem_root": bool(jobs.dem_root())})


def _search(body):
    product = str(body.get('product') or '')
    bbox = body.get('bbox')
    items = usgs.products(bbox, product)
    chosen, skipped = usgs.candidates(items, product)
    from terrain_provider import LOCAL_CATALOG

    usgs.mark_held(chosen, LOCAL_CATALOG.entries(), product, layers.relative)
    return product, chosen, skipped


@admin_bp.route('/data/usgs/search', methods=['POST'])
@admin_required
def data_search():
    _check_csrf()
    _readable()
    body = request.get_json(silent=True) or {}
    try:
        product, chosen, skipped = _search(body)
    except usgs.SearchError as error:
        return _refuse(str(error))
    new = [c for c in chosen if not c.held]
    root = jobs.dem_root()
    room = None
    refusal = None
    if data_admin.can_write():
        try:
            jobs.check_job(jobs.plan([c.as_dict() for c in chosen], replace=False), root)
        except volumes.NoRoom as error:
            refusal = str(error) if new else None
    if root is not None:
        usage = volumes.usage(root)
        room = {"free": usage["free"], "reserve": usage["reserve"]}
    return _json({"ok": True, "product": product, "files": [c.for_page() for c in chosen], "skipped": skipped,
                  "new_files": len(new), "new_bytes": sum(c.size for c in new),
                  "held_bytes": sum(c.size for c in chosen if c.held), "room": room, "refusal": refusal,
                  "can_write": data_admin.can_write()})


@admin_bp.route('/data/jobs', methods=['POST'])
@admin_required
def data_job_create():
    _check_csrf()
    refused = _writable()
    if refused:
        return refused
    body = request.get_json(silent=True) or {}
    replace = bool(body.get('replace'))
    try:
        # Searched again here: what is downloaded is what TNM says now, never a list the page sent.
        product, chosen, _ = _search(body)
        files = jobs.plan([c.as_dict() for c in chosen], replace=replace)
        jobs.check_job(files, jobs.dem_root())
    except (usgs.SearchError, volumes.NoRoom) as error:
        return _refuse(str(error))
    admin = request.admin
    with _creating:
        if DataJob.query.filter(DataJob.state.in_(("queued", "running"))).count():
            return _refuse(BUSY, 409)
        job = DataJob(id=jobs.new_id(), product=product, files=files, files_total=len(files),
                      bytes_total=sum(f["size"] for f in files), replace=replace, requested_by=admin.id,
                      requested_by_name=admin.name or admin.email or '')
        db.session.add(job)
        db.session.commit()
    jobs.RUNNER.enqueue(current_app._get_current_object(), job.id)
    return _json({"ok": True, "job": _job_dict(job)}, 202)


@admin_bp.route('/data/jobs.json')
@admin_required
def data_jobs():
    _readable()
    _resume()
    recent = DataJob.query.order_by(DataJob.created_at.desc()).limit(RECENT_JOBS).all()
    return _json({"jobs": [_job_dict(job) for job in recent]})


@admin_bp.route('/data/jobs/<job_id>.json')
@admin_required
def data_job(job_id):
    _readable()
    job = db.session.get(DataJob, job_id)
    if job is None:
        abort(404)
    return _json({"job": _job_dict(job)})


@admin_bp.route('/data/jobs/<job_id>/cancel', methods=['POST'])
@admin_required
def data_job_cancel(job_id):
    _check_csrf()
    refused = _writable()
    if refused:
        return refused
    job = db.session.get(DataJob, job_id)
    if job is None:
        abort(404)
    if job.state in ("queued", "running"):
        # Asked of the job itself, always: a runner that picks it up meanwhile reads it before fetching anything.
        job.cancel_requested = True
        if job.state == "queued" and not jobs.RUNNER.knows(job.id):
            job.state, job.finished_at = "cancelled", datetime.utcnow()
        db.session.commit()
        if job.kind == chart_job.KIND and job.requested_by_name == chart_state.AUTOMATIC and job.build:
            # An automatic build cancelled is the admin's choice: not started again, and no "given up" email (§5.7).
            where = chart_state.root()
            if where is not None and chart_locks.supported():
                admin = request.admin
                try:
                    chart_auto.cancelled(where, job.build, admin.name or admin.email or '')
                except (chart_state.StateError, OSError):
                    current_app.logger.warning("charts: an automatic build's cancel could not be recorded")
    db.session.commit()
    return _json({"ok": True, "job": _job_dict(job)})


# ---------------------------------------------------------------------------------------------------- VFR charts


def _chart_refusal(where):
    """Why charts cannot be built here, in words, or None."""
    if where is None:
        return "CHART_TILES_DIR is not set on this server."
    if not chart_locks.supported():
        return "Chart builds need a Linux or macOS server: this one cannot lock a file across processes."
    return None


HISTORY_SHOWN = 20


def _build_line(name, info, roles, now):
    """A build as the Charts card lists it: its state (live, previous, scheduled next, or built) and the actions of §5.5 it is
    offered. What is offered is worked out on ``effective(state, now)``, as the actions themselves are."""
    role = next((r for r in ("live", "previous", "next") if roles[r] == name), "built")
    cycle = chart_state.cycle_of(info)
    in_effect = cycle is not None and chart_catalog.effective_instant(cycle) <= now
    actions = []
    if role != "live" and cycle is not None:
        if in_effect:
            actions.append("switch")
        elif role != "next":
            actions.append("schedule")
    if role == "built":
        actions.append("delete")
    return {"name": name, "role": role, "cycle": info.get("cycle"), "effective": info.get("effective"),
            "expires": info.get("expires"), "expired": (chart_state.instant(info.get("expires")) or now) <= now,
            "in_effect": in_effect, "made": info.get("made"), "by": info.get("by"), "bytes": info.get("bytes"),
            "tiles": info.get("tiles"), "retired": info.get("retired"), "actions": actions,
            "held_back": [{"title": h.get("title"), "reason": h.get("reason"), "kept": h.get("kept")}
                          for h in info.get("held_back") or [] if isinstance(h, dict)]}


@admin_bp.route('/data/charts.json')
@admin_required
def data_charts():
    """The VFR charts panel (docs/VFR_CHARTS.md §5.6): what the chart folder holds and what each build is, the actions that apply,
    the cycles and what the automatic cycle did or will do for each, and the history. Asks the FAA whether a cycle is posted (kept
    10 minutes). Reads ``state.json`` as every reader does, with a switch whose time has passed applied, and writes nothing to it."""
    _readable()
    _resume()
    where = chart_state.root()
    now = datetime.now(timezone.utc)
    mode = chart_auto.mode()
    body = {"configured": where is not None, "can_write": data_admin.can_write(), "refusal": _chart_refusal(where),
            "estimate_bytes": chart_job.CONUS_ESTIMATE_BYTES, "now": chart_state.iso(now), "builds": [], "cycles": [],
            "room": None, "serving": None, "history": [],
            "auto": {"looks": mode["looks"], "builds": mode["builds"], "why": mode["why"], "last": None, "next": None,
                     "busy": False, "error": None}}
    if body["refusal"]:
        return _json(body)
    try:
        held = chart_state.serving(where, now)
    except chart_state.StateError as error:
        body["refusal"] = str(error)
        return _json(body)
    roles = chart_state.roles(held)
    nxt = held.get("next") if isinstance(held.get("next"), dict) else None
    body["serving"] = {"live": roles["live"], "previous": roles["previous"],
                       "next": {"build": nxt.get("build"), "at": nxt.get("at"), "by": nxt.get("by")} if nxt else None,
                       "roll_back": bool(roles["previous"] and isinstance(held["builds"].get(roles["previous"]), dict)),
                       "cancel_schedule": nxt is not None}
    looks = held.get("looks") if isinstance(held.get("looks"), dict) else {}
    body["auto"].update({key: looks.get(key) for key in ("last", "next", "error")}, busy=bool(looks.get("busy")))
    builds = held["builds"]
    body["builds"] = [_build_line(name, info, roles, now)
                      for name, info in sorted(builds.items(), key=lambda kv: chart_state._order(kv[0]), reverse=True)
                      if isinstance(info, dict)]
    for cycle, which in ((faa.current_cycle(now), "current"), (faa.next_cycle(now), "next")):
        posted = faa.posted(cycle)
        key = cycle.isoformat()
        body["cycles"].append({"cycle": key, "which": which,
                               "effective": chart_catalog.effective_instant(cycle).strftime("%Y-%m-%dT%H:%MZ"),
                               "ends": chart_catalog.cycle_ends(cycle).strftime("%Y-%m-%dT%H:%MZ"),
                               "posted": posted,
                               "built": [name for name in builds if name.startswith(key + ".")],
                               "serving": next((r for r in ("live", "next") if roles[r] and roles[r].startswith(key + ".")),
                                               None),
                               "auto": chart_auto.describe(held, cycle, posted, now, mode)})
    body["history"] = [line for line in reversed(held["history"][-HISTORY_SHOWN:]) if isinstance(line, dict)]
    if data_admin.can_write():
        try:
            volumes.check_room(where, chart_job.CONUS_ESTIMATE_BYTES, label=chart_job.LABEL)
        except volumes.NoRoom as error:
            body["room"] = str(error)
    return _json(body)


def _chart_action(act):
    """One of §5.5's actions, under the CSRF check and the write gate, by the admin signed in. A refusal is in words (400); it
    answers at once (Cloudflare ends a request at 100 s)."""
    _check_csrf()
    refused = _writable()
    if refused:
        return refused
    where = chart_state.root()
    why = _chart_refusal(where)
    if why:
        return _refuse(why)
    admin = request.admin
    by = admin.name or admin.email or ''
    try:
        said = act(where, by, datetime.now(timezone.utc))
    except chart_state.Refused as error:
        return _refuse(str(error))
    except ValueError:
        return _refuse("That is not a build's name.")
    except (chart_state.StateError, OSError) as error:
        current_app.logger.warning("charts: state.json could not be changed (%s)", error.__class__.__name__)
        return _refuse(str(error) if isinstance(error, chart_state.StateError) else
                       "The chart folder's state.json could not be written.", 500)
    return _json({"ok": True, **(said or {})})


@admin_bp.route('/data/charts/builds/<build>/switch', methods=['POST'])
@admin_required
def data_chart_switch(build):
    """Switch to this build now (§5.5): only one whose cycle is in effect."""
    return _chart_action(lambda where, by, now: chart_state.switch(where, chart_state.check_name(build), by, now))


@admin_bp.route('/data/charts/builds/<build>/schedule', methods=['POST'])
@admin_required
def data_chart_schedule(build):
    """Switch to this build at its cycle's effective time (§5.5): only one whose cycle is not yet in effect."""
    return _chart_action(lambda where, by, now: {"at": chart_state.schedule(where, chart_state.check_name(build), by, now)})


@admin_bp.route('/data/charts/builds/<build>/delete', methods=['POST'])
@admin_required
def data_chart_delete(build):
    """Takes a build out of ``state.json`` and deletes its folder (§5.5): never the live, previous or scheduled one."""
    return _chart_action(lambda where, by, now: chart_state.delete_build(where, chart_state.check_name(build), by, now))


@admin_bp.route('/data/charts/roll-back', methods=['POST'])
@admin_required
def data_chart_roll_back():
    """Live and previous change places (§5.5); the scheduled switch is kept."""
    return _chart_action(lambda where, by, now: chart_state.roll_back(where, by, now))


@admin_bp.route('/data/charts/schedule/cancel', methods=['POST'])
@admin_required
def data_chart_cancel_schedule():
    """Clears the scheduled switch (§5.5): refused once its time has come."""
    return _chart_action(lambda where, by, now: chart_state.cancel_schedule(where, by, now))


@admin_bp.route('/data/charts/cycles/<cycle>/try-again', methods=['POST'])
@admin_required
def data_chart_try_again(cycle):
    """Clears a given-up cycle's tries, so the automatic cycle builds it at its next look (§5.6, §5.7)."""
    def act(where, by, now):
        key = date.fromisoformat(cycle).isoformat()
        chart_auto.try_again(where, key, by, now)

    return _chart_action(act)


@admin_bp.route('/data/charts/builds', methods=['POST'])
@admin_required
def data_chart_build():
    """Builds a cycle of the FAA's VFR charts (docs/VFR_CHARTS.md §5.3): the current one or the next, once the FAA has posted it.
    What is downloaded is the catalog's, never a list the page sent."""
    _check_csrf()
    refused = _writable()
    if refused:
        return refused
    where = chart_state.root()
    why = _chart_refusal(where)
    if why:
        return _refuse(why)
    body = request.get_json(silent=True) or {}
    now = datetime.now(timezone.utc)
    offered = {faa.current_cycle(now), faa.next_cycle(now)}
    try:
        cycle = date.fromisoformat(str(body.get("cycle") or ""))
    except ValueError:
        cycle = None
    if cycle not in offered:
        return _refuse("Only the current cycle or the next can be built.")
    posted = faa.posted(cycle)
    if posted is None:
        return _refuse("The FAA could not be asked whether that cycle is posted. Try again in a minute.")
    if not posted:
        return _refuse(f"The FAA has not posted the charts of {cycle.isoformat()} yet.")
    try:
        volumes.check_room(where, chart_job.CONUS_ESTIMATE_BYTES, label=chart_job.LABEL)
    except volumes.NoRoom as error:
        return _refuse(str(error))
    admin = request.admin
    by = admin.name or admin.email or ''
    try:
        # A build an admin makes leaves the cycle to them: the automatic cycle neither builds, schedules nor switches it (§5.7).
        job = chart_job.start(current_app._get_current_object(), cycle, by_id=admin.id, by_name=by,
                              then=lambda st, name, _job: chart_auto.admin_started(st, name, by, now), now=now)
    except jobs.Busy:
        return _refuse(BUSY, 409)
    except (chart_state.StateError, OSError) as error:
        current_app.logger.warning("charts: a build number could not be taken (%s)", error.__class__.__name__)
        return _refuse(str(error) if isinstance(error, chart_state.StateError) else
                       "The chart folder's state.json could not be written.")
    return _json({"ok": True, "job": _job_dict(job)}, 202)


@admin_bp.route('/data/dems/tag-usgs', methods=['POST'])
@admin_required
def data_tag_usgs():
    """Tags as NAVD88 the DEMs whose names are USGS's 3DEP products and that state no datum (§10.6, Q5)."""
    _check_csrf()
    refused = _writable()
    if refused:
        return refused
    from terrain_provider import LOCAL_CATALOG

    tagged, refused_names = 0, []
    for entry in LOCAL_CATALOG.entries():
        if entry.kind != "cog" or "NAVD88" in (entry.vertical_datum or "").upper():
            continue
        if not usgs.is_3dep_name(entry.path):
            continue
        try:
            volumes.check_room(entry.path, 0, label="Its folder")
            validate.tag_navd88(entry.path)
            tagged += 1
        except volumes.NoRoom as error:
            refused_names.append(f"{layers.relative(entry.path)}: {error}")
        except ParseError:
            refused_names.append(f"{layers.relative(entry.path)}: its .aux.xml cannot be read, so it is left as it is.")
        except OSError as error:
            refused_names.append(f"{layers.relative(entry.path)}: could not be written ({error.__class__.__name__}).")
    if tagged:
        LOCAL_CATALOG.refresh()
    return _json({"ok": True, "tagged": tagged, "refused": refused_names[:20]})
