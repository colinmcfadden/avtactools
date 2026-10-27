"""A survey's one-page PDF (docs/LZ_SURVEYS.md §5): the page model (``compile.compile_page``) laid out as HTML, its figures drawn
(``figures``), and Chromium's PDF of it through Gotenberg, as the mission brief is printed (``brief.gotenberg``).

The page prints its own UNCLASSIFIED lines, header and footer, so Chromium's are not used. Every value goes through Jinja's escaping;
the only markup not escaped is the figures' SVG, which ``figures`` writes and escapes itself. The same HTML, with its pictures put in
it, is the editor's preview where the server has no Gotenberg (``preview_html``).
"""

from __future__ import annotations

import base64
from pathlib import Path

from jinja2 import Environment, FileSystemLoader, select_autoescape
from markupsafe import Markup

from brief import gotenberg
from survey import figures

_TEMPLATES = Path(__file__).resolve().parent / "templates"
_env = Environment(loader=FileSystemLoader(str(_TEMPLATES)), autoescape=select_autoescape(["html"]))

PAPER = ("8.5", "11")
MARGINS = ("0.3", "0.3", "0.3", "0.3")
_EXTENSIONS = {"image/jpeg": ".jpg", "image/png": ".png"}


def _figures(model, asset_bytes):
    """The page's pictures as {file name: bytes}, and what the template needs of each."""
    files = {}
    shown = {"plan": None, "view3d": None, "photos": []}
    plan = figures.plan(model.get("plan")) if model.get("plan") else None
    if plan:
        files["plan.jpg"] = plan["jpeg"]
        shown["plan"] = {"file": "plan.jpg", "svg": Markup(plan["svg"])}

    def picture(asset_id, stem):
        found = asset_bytes(asset_id)
        if not found:
            return None
        data, content_type = found
        name = stem + _EXTENSIONS.get(content_type, ".jpg")
        files[name] = data
        return name

    if model.get("view3d"):
        name = picture(model["view3d"]["assetId"], "view3d")
        if name:
            shown["view3d"] = {"file": name, "caption": model["view3d"]["caption"]}
    for i, photo in enumerate(model.get("photos") or []):
        name = picture(photo["assetId"], f"photo-{i + 1}")
        if name:
            shown["photos"].append({"file": name, "caption": photo["caption"]})
    return files, shown


def build(model, asset_bytes):
    """(index.html, {file name: bytes}). ``asset_bytes(id)`` is a survey picture's (bytes, content type), or None when it is gone."""
    files, shown = _figures(model, asset_bytes)
    profiles = model.get("profiles")
    clearance = model.get("clearance") if isinstance(model.get("clearance"), dict) else {}
    graphs = None
    if profiles:
        along = figures.profile(profiles["along"], "side", treetops=clearance.get("alongTreetopsFt"))
        across = figures.profile(profiles["across"], "rear", width=380)
        graphs = {"along": Markup(along) if along else None, "across": Markup(across) if across else None}
    survey_profile = figures.survey_profile(model.get("surveyProfile"))
    html = _env.get_template("survey.html").render(
        m=model,
        title=f"HLZ survey · {model.get('name') or ''}",
        plan=shown["plan"],
        view3d=shown["view3d"],
        photos=shown["photos"],
        graphs=graphs,
        compass=Markup(figures.compass(model.get("shape"), (model.get("plan") or {}).get("approachDeg"))),
        survey_profile=Markup(survey_profile) if survey_profile else None,
    )
    return html, files


def print_pdf(model, asset_bytes):
    """The page as a PDF, or gotenberg.Unavailable / gotenberg.Failed."""
    html, files = build(model, asset_bytes)
    return gotenberg.print_pdf(html, None, None, files, PAPER, MARGINS)


def preview_html(model, asset_bytes):
    """The page as one HTML document with its pictures in it, for the editor to show where there is no Gotenberg. Shown in a frame
    that runs nothing, it is only ever the server's own markup."""
    html, files = build(model, asset_bytes)
    for name, data in files.items():
        content_type = "image/png" if name.endswith(".png") else "image/jpeg"
        html = html.replace(f'src="{name}"', f'src="data:{content_type};base64,{base64.b64encode(data).decode("ascii")}"')
    return html
