"""A brief's pages as HTML, with their figures, ready for Gotenberg (docs/MISSION_BRIEF.md §2.7).

The client compiles the brief (compileBrief) and sends the model; this lays it out page by page as the model's
``pages`` say, one step to a page, then the appendices. Every value is the model's and goes through Jinja's escaping;
the only markup not escaped is the figures' SVG, which figures.py writes and escapes itself.
"""

import json
from datetime import datetime, timezone
from pathlib import Path

from jinja2 import Environment, FileSystemLoader, select_autoescape
from markupsafe import Markup

from brief import figures

_TEMPLATES = Path(__file__).resolve().parent / 'templates'
_env = Environment(loader=FileSystemLoader(str(_TEMPLATES)), autoescape=select_autoescape(['html']))

PAPERS = {'letter': ('8.5', '11', 'letter'), 'a4': ('8.27', '11.69', 'A4')}
ROLE_WORDS = {'LZ': 'LZ', 'PZ': 'PZ', 'FARP': 'FARP', 'HA': 'Holding area', 'LAAGER': 'Laager', 'FALSE_LZ': 'False insertion LZ'}
# Figures drawn for one brief: the steps' and appendices' (about 30 for a big one) and its strip charts (compileBrief makes
# at most 60), each a set of map tiles to fetch.
MAX_FIGURES = 100


class BadModel(ValueError):
    """The body is not a brief model this server can lay out."""


def _text(value, limit=2000):
    return value[:limit] if isinstance(value, str) else ''


def check_model(model):
    """The model as compileBrief makes it, or BadModel: only its shape is read, never trusted to be anything else."""
    if not isinstance(model, dict) or model.get('format') != 'ezpz-brief-model':
        raise BadModel('Send the brief model compileBrief makes.')
    for key in ('steps', 'pages'):
        if not isinstance(model.get(key), list) or not model[key]:
            raise BadModel(f'The brief has no {key}.')
    if not all(isinstance(step, dict) and isinstance(step.get('id'), str) for step in model['steps']):
        raise BadModel('A step is not readable.')
    if not all(isinstance(page, dict) and isinstance(page.get('n'), int) for page in model['pages']):
        raise BadModel('A page is not readable.')
    if len(model['pages']) > 200:
        raise BadModel('The brief has too many pages.')
    return model


def _source(model):
    options = model.get('options') if isinstance(model.get('options'), dict) else {}
    source = options.get('figureMap')
    return source if isinstance(source, str) and source in figures.SOURCES else 'satellite'


def _strip_source(model):
    options = model.get('options') if isinstance(model.get('options'), dict) else {}
    source = options.get('stripMap')
    return source if isinstance(source, str) and source in figures.SOURCES else 'topo'


def _scale_words(scale):
    return f'1:{int(scale):,}' if isinstance(scale, (int, float)) and not isinstance(scale, bool) else ''


def _paper(model):
    options = model.get('options') if isinstance(model.get('options'), dict) else {}
    return PAPERS.get(options.get('paper'), PAPERS['letter'])


def build(model, now=None):
    """The brief as (index.html, header.html, footer.html, {file name: bytes}, paper): the page, Chromium's header and
    footer, the figures' images, and the paper's width and height in inches."""
    check_model(model)
    options = model.get('options') if isinstance(model.get('options'), dict) else {}
    geometry = model.get('geometry') if isinstance(model.get('geometry'), dict) else {}
    source = _source(model)
    steps = {step['id']: step for step in model['steps']}
    files = {}
    drawn = {}

    def render_figure(spec, draw=None):
        if not isinstance(spec, dict):
            return None
        key = json.dumps(spec, sort_keys=True)
        if key not in drawn:
            if len(files) >= MAX_FIGURES:
                return None
            made = draw(spec) if draw else figures.draw(spec, geometry, source)
            if made is None:
                drawn[key] = None
            else:
                name = f'fig-{len(files) + 1}.jpg'
                files[name] = made['jpeg']
                drawn[key] = {'file': name, 'svg': Markup(made['svg'])}
        return drawn[key]

    strip_source = _strip_source(model)
    strips = model.get('strips') if isinstance(model.get('strips'), list) else []
    strips_by_id = {s['id']: s for s in strips if isinstance(s, dict) and isinstance(s.get('id'), str)}
    strips_shown = set()

    routes = geometry.get('routes') if isinstance(geometry.get('routes'), dict) else {}
    stops = geometry.get('stops') if isinstance(geometry.get('stops'), dict) else {}
    flow = {f.get('ref'): f for f in model.get('flow') or [] if isinstance(f, dict)}

    pages = []
    for page in model['pages']:
        kind = page.get('kind')
        out = {'n': page['n'], 'kind': kind}
        if kind in ('cover', 'step'):
            step = steps.get(page.get('stepId'))
            if not step:
                continue
            spec = step.get('figure')
            shown = dict(step)
            shown['paragraph'] = _text(step.get('number')).split('.')[0]
            shown['figure_render'] = render_figure(spec)
            shown['caption'] = f"{_text(step.get('title'))}: drawn from the plan."
            out['step'] = shown
            if kind == 'cover':
                overview = next((s.get('figure') for s in model['steps'] if isinstance(s.get('figure'), dict)
                                 and s['figure'].get('kind') == 'overview'), None)
                out['figure'] = render_figure(overview)
        elif kind == 'lzCard':
            stop = stops.get(page.get('ref'))
            if not stop:
                continue
            spec = {'kind': 'stop', 'ref': page.get('ref'), 'stops': [page.get('ref')], 'routes': list(routes),
                    'bounds': figures.bounds_around([(stop.get('lat'), stop.get('lon'))] + [tuple(p) for p in stop.get('boundary') or []])}
            entry = flow.get(page.get('ref'), {})
            facts = [
                {'label': 'ROLE', 'value': ROLE_WORDS.get(stop.get('role'), stop.get('role'))},
                {'label': 'GRID', 'value': entry.get('grid')},
                {'label': 'ARRIVE', 'value': entry.get('arrive')},
                {'label': 'LAND', 'value': f"{int(stop['landingHeading']):03d}°" if isinstance(stop.get('landingHeading'), (int, float)) else None},
                {'label': 'T/O', 'value': f"{int(stop['takeoffHeading']):03d}°" if isinstance(stop.get('takeoffHeading'), (int, float)) else None},
                {'label': 'GO-AROUND', 'value': stop.get('goAround')},
                {'label': 'AIRCRAFT', 'value': str(len(stop.get('aircraft') or [])) if stop.get('aircraft') else None},
            ]
            out.update(stop=stop, facts=facts, figure=render_figure(spec), role_word=ROLE_WORDS.get(stop.get('role'), 'LZ/PZ'))
        elif kind == 'navLog':
            route = routes.get(page.get('ref'))
            if not route:
                continue
            spec = {'kind': 'route', 'ref': page.get('ref'), 'routes': [page.get('ref')], 'stops': list(stops),
                    'bounds': figures.bounds_around([tuple(p) for p in route.get('line') or []], pad_m=600)}
            out.update(route=route, figure=render_figure(spec))
        elif kind == 'strips':
            shown = []
            for strip_id in (page.get('strips') or [])[:3] if isinstance(page.get('strips'), list) else []:
                # Each strip once in a brief, however often a page names it.
                strip = strips_by_id.get(strip_id) if isinstance(strip_id, str) and strip_id not in strips_shown else None
                if not figures.valid_strip(strip):
                    continue
                strips_shown.add(strip_id)
                figure = render_figure(strip, lambda spec: figures.draw_strip(spec, strip_source))
                if not figure:
                    continue
                grid = strip.get('grid') if isinstance(strip.get('grid'), dict) else {}
                squares = [_text(q, 12) for q in grid.get('squares') or []][:6]
                others = [_text(q, 12) for q in grid.get('others') or []][:6]
                shown.append({'figure': figure, 'name': _text(strip.get('name'), 100), 'part': strip.get('part'),
                              'parts': strip.get('parts'), 'course': _text(strip.get('courseText'), 8),
                              'scale': _scale_words(strip.get('scale')), 'squares': ' → '.join(squares),
                              'others': ', '.join(others), 'gridded': bool(grid.get('lines'))})
            if not shown:
                continue
            out.update(title=_text(page.get('title'), 200), strips=shown)
        elif kind == 'iimc':
            item = next((i for s in model['steps'] for i in s.get('items') or [] if i.get('id') == page.get('itemId')), None)
            out['text'] = item.get('text') if item and not item.get('writeIn') else None
        else:
            continue
        pages.append(out)

    # The contents are of the pages laid out: a page this server cannot draw (a kind a newer client sends, or strips that
    # would not draw) is not promised. No count of pages: Chromium decides where a long one breaks.
    laid_out = {page['n'] for page in pages}
    contents = []
    for page in model['pages']:
        if page['n'] not in laid_out:
            continue
        step = steps.get(page.get('stepId')) if page.get('kind') in ('cover', 'step') else None
        contents.append({'n': page['n'], 'title': f"{step.get('number')} {step.get('title')}" if step else _text(page.get('title')),
                         'who': step.get('who') if step else ''})

    template = model.get('template') if isinstance(model.get('template'), dict) else {}
    mission = model.get('mission') if isinstance(model.get('mission'), dict) else {}
    name = _text(mission.get('name'), 100)
    if not name or name == 'the mission':
        name = 'MISSION BRIEF'
    stamp = (now or datetime.now(timezone.utc)).strftime('%d %b %Y %H:%MZ')
    width, height, size = _paper(model)
    constants = [c for c in model.get('constants') or [] if isinstance(c, dict)] if options.get('constants', True) else []
    aircraft = next((f.get('value') for s in model['steps'] for f in s.get('facts') or [] if f.get('key') == 'flight.aircraft' and f.get('value')), None)

    html = _env.get_template('brief.html').render(
        title=f'{name} · brief', pages=pages, contents=contents, format_title=_text(template.get('title')).upper(),
        mission_name=name, mission_date=_text(mission.get('date')), summary=_text(mission.get('summary')), aircraft=aircraft,
        generated=stamp, constants=constants,
        problems=[_text(p.get('message')) for p in model.get('problems') or [] if isinstance(p, dict)],
        write_ins=options.get('writeIns', True), show_script=options.get('script', True), page_size=size,
    )
    header = _env.get_template('header.html').render(mission_name=name, format_title=_text(template.get('shortTitle')))
    footer = _env.get_template('footer.html').render()
    return html, header, footer, files, (width, height)
