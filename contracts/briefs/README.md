# contracts/briefs/

The mission brief's formats, as data (`docs/MISSION_BRIEF.md` §2.1). The compiler (`compileBrief`: the web's
`feature/brief/compile.js`, Android's `core-brief`) knows how to bind and write; it knows nothing about either format. Everything a
format says is here, and both apps bundle these files unchanged.

```
briefs/
├─ amb.json         the non-tactical multi-ship / air movement brief (nine paragraphs)
├─ hasty.json       the tactical hasty multi-ship brief (thirty-two items)
├─ bindings.json    every value the compiler can find in the plan: the contract between a template and compileBrief
├─ constants.json   the mission constants a brief offers (the band on every page, the box on the sandtable)
└─ standards.json   the standard procedures a contingency's "Standard" applies, and the standard numbers they use
```

The formats come from the Aviation Branch Handbook (Annex A to the Aviation Branch Operations SOP, 15 July 2024). The owner confirmed on
2026-10-16 that their item lists and standard values are releasable; they are written here in the app's own words. **The handbook itself
is never committed.**

`frontend/src/contracts/briefTemplates.test.js` holds every rule below; Android's `core-brief` will run the same checks.

## A template

```jsonc
{
  "format": "ezpz-brief-template",   // what this file is
  "id": "amb", "version": 1,         // a brief document records both; a new version migrates answers by item id, never by position
  "title": "…", "shortTitle": "…", "source": "…",
  "defaultWho": "AMC",               // who briefs a section that does not say
  "roles": { "PZ": ["4.d"], "LZ": ["4.e"], "HA": [] },   // where each kind of stop is briefed; [] = only in the timeline
  "paragraphs": [
    { "number": "4", "title": "Execution", "who": "Flight lead",
      "sections": [
        { "id": "4.e", "title": "Landing sequence",
          "repeat": "LZ",            // one page and one sandtable step per stop of that role (or "route", per route in the flow)
          "step": "4.e",             // side-by-side sections that share a step (and a repeat) are one page and one step
          "stepTitle": "…",          // the title of such a step, on its first section (required when a step has several)
          "figure": "stop",          // overview | departure | route | stop | rally | none
          "facts": ["stop.grid", "stop.elevation"],   // bindings shown in the page's table besides the items
          "items": [
            { "id": "i", "label": "Location", "kind": "text", "bind": "stop.grid" },
            { "id": "iii", "label": "Performance planning", "kind": "text" }
          ],
          "script": ["{bind:stop.name} is {i} at {bind:stop.elevation}.", "Performance planning: {iii}."] }
      ] }
  ]
}
```

- **Ids.** A section id is unique in its template. An item id is unique in its section; its full id is `<section>.<item>` (`4.e.iii`).
  Item ids are lower case letters, roman numerals and dots (`b.ii`).
- **Kinds.** `text` (one line), `note` (several lines), `time`, `choice` (one of `options`), `procedure` (Standard / Custom / Blank:
  `standard` names an entry of `standards.json`; Custom shows `fields`), `list` (rows the person adds), `info` (nothing to fill: the
  section says it, like *Questions*).
- **Bindings.** `bind` names an entry of `bindings.json` whose `scope` fits the section: `brief` anywhere, `stop` only in a section
  repeated for a stop role, `route` only in one repeated per route. A bound item is filled from the plan and the person may type over it.
  An item with no binding and no answer prints as a line to write on.
- **Scripts.** Sentences with placeholders: `{a}` / `{b.ii}` an item of this section, `{item:5.a.v}` an item of another section,
  `{bind:<name>}` a binding, `{const:<id>}` a constant of `constants.json`. Nothing else in braces. A placeholder with no value prints as
  a write-in line. A procedure item's placeholder is its procedure's script as chosen (standard, custom or blank).
- **Roles** are `PZ`, `LZ`, `FARP`, `HA` (holding area), `LAAGER` and `FALSE_LZ` (false insertion). Every role is listed in every
  template, each with the sections repeated for it; `"route"` sections repeat for each route in the flow.
- **Order.** Steps are briefed in template order, except that a run of side-by-side repeated sections is briefed in flow
  order: each stop and route as the flight meets it, with every section of the run that repeats for it.
- **Figures** are what the compiler draws for the step: `overview` (the whole mission), `departure` (the departure point and the start of
  the first route), `route` (that route, its current leg lit), `stop` (that LZ/PZ's diagram), `rally` (the routes with the rally points),
  `none`.

## bindings.json

`{ "<name>": { "scope": "brief" | "stop" | "route", "type": "<type>", "description": "…" } }`. `type` is how the compiler writes the
value (`text`, `time`, `heading`, `feet`, `knots`, `grid`, `count`, `list`). Every binding here must be implemented by `compileBrief`
and held by its fixtures (B1); a binding no template uses is still allowed (a later format).

## constants.json

`{ "constants": [ { "id", "label", "short", "unit"?, "bind"?, "standard"?, "offered" } ] }`. `bind` is where the plan gives a value
(the brief derives it and says so); `standard` names a `standards.json` value used when the plan has none; `offered: true` means it is
in a new brief, else it is a suggestion under *Add constant*.

## standards.json

`{ "values": { "<id>": { "value", "unit"?, "label" } }, "procedures": { "<id>": { "title", "summary", "script": [...], "fields"? } } }`.
A procedure's script may use `{value:<id>}` (a standard value) and `{field:<id>}` (one of its own custom fields), besides the template
placeholders.
