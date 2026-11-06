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
from charts import catalog as chart_catalog, faa, job as chart_job, locks as chart_locks, state as chart_state
from models import db, DataJob
from routes.admin_routes import admin_bp, admin_required, _check_csrf

RECENT_JOBS = 10
# One job at a time: the check and the insert under one lock (one gunicorn process, AGENTS.md §9).
_creating = threading.Lock()
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
    return _json({"ok": True, "job": _job_dict(job)})


# ---------------------------------------------------------------------------------------------------- VFR charts


def _chart_refusal(where):
    """Why charts cannot be built here, in words, or None."""
    if where is None:
        return "CHART_TILES_DIR is not set on this server."
    if not chart_locks.supported():
        return "Chart builds need a Linux or macOS server: this one cannot lock a file across processes."
    return None


def _build_line(name, info):
    return {"name": name, "cycle": info.get("cycle"), "effective": info.get("effective"), "expires": info.get("expires"),
            "made": info.get("made"), "by": info.get("by"), "bytes": info.get("bytes"), "tiles": info.get("tiles"),
            "held_back": [{"title": h.get("title"), "reason": h.get("reason"), "kept": h.get("kept")}
                          for h in info.get("held_back") or [] if isinstance(h, dict)]}


@admin_bp.route('/data/charts.json')
@admin_required
def data_charts():
    """The VFR charts panel: what the chart folder holds, the cycles that can be built, and whether one can be now. Asks the FAA
    whether a cycle is posted (kept 10 minutes)."""
    _readable()
    _resume()
    where = chart_state.root()
    now = datetime.now(timezone.utc)
    body = {"configured": where is not None, "can_write": data_admin.can_write(), "refusal": _chart_refusal(where),
            "estimate_bytes": chart_job.CONUS_ESTIMATE_BYTES, "builds": [], "cycles": [], "room": None}
    if body["refusal"]:
        return _json(body)
    try:
        held = chart_state.read(where)
    except chart_state.StateError as error:
        body["refusal"] = str(error)
        return _json(body)
    builds = held["builds"]
    body["builds"] = [_build_line(name, info) for name, info in sorted(builds.items(), key=lambda kv: chart_state._order(kv[0]),
                                                                       reverse=True) if isinstance(info, dict)]
    for cycle, which in ((faa.current_cycle(now), "current"), (faa.next_cycle(now), "next")):
        body["cycles"].append({"cycle": cycle.isoformat(), "which": which,
                               "effective": chart_catalog.effective_instant(cycle).strftime("%Y-%m-%dT%H:%MZ"),
                               "posted": faa.posted(cycle),
                               "built": [name for name in builds if name.startswith(cycle.isoformat() + ".")]})
    if data_admin.can_write():
        try:
            volumes.check_room(where, chart_job.CONUS_ESTIMATE_BYTES, label=chart_job.LABEL)
        except volumes.NoRoom as error:
            body["room"] = str(error)
    return _json(body)


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
    files = chart_job.files_for(cycle, chart_job.only())
    with _creating:
        if DataJob.query.filter(DataJob.state.in_(("queued", "running"))).count():
            return _refuse(BUSY, 409)
        try:
            name = chart_state.take_number(where, cycle)
        except (chart_state.StateError, OSError) as error:
            current_app.logger.warning("charts: a build number could not be taken (%s)", error.__class__.__name__)
            return _refuse(str(error) if isinstance(error, chart_state.StateError) else
                           "The chart folder's state.json could not be written.")
        job = DataJob(id=jobs.new_id(), kind=chart_job.KIND, product=chart_job.PRODUCT, files=files, files_total=len(files),
                      bytes_total=0, build=name, stage=chart_job.STAGES[0], requested_by=admin.id,
                      requested_by_name=admin.name or admin.email or '')
        db.session.add(job)
        db.session.commit()
    jobs.RUNNER.enqueue(current_app._get_current_object(), job.id)
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
