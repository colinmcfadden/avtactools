// The mission brief's formats are data (contracts/briefs/, its README is the format). This test holds every rule
// the compiler will lean on, so a template that breaks one fails here and not in a brief someone is about to give.
// Android's core-brief runs the same checks against the same files.
const fs = require("fs");
const path = require("path");

const BRIEFS = path.resolve(__dirname, "../../../contracts/briefs");
const read = (name) => JSON.parse(fs.readFileSync(path.join(BRIEFS, name), "utf8"));

const TEMPLATES = ["amb", "hasty"];
const ROLES = ["PZ", "LZ", "FARP", "HA", "LAAGER", "FALSE_LZ"];
const KINDS = ["text", "note", "time", "choice", "procedure", "list", "info"];
const FIGURES = ["overview", "departure", "route", "stop", "rally", "none"];
const SCOPES = ["brief", "stop", "route"];
const TYPES = ["text", "time", "heading", "feet", "knots", "grid", "count", "list"];
const ITEM_ID = /^[a-z]+(\.[a-z]+)*$/;

const { bindings } = read("bindings.json");
const { constants } = read("constants.json");
const standards = read("standards.json");

// What a section repeats for, as the scope its bindings may come from.
const scopeOf = (section) => {
  if (section.repeat === undefined) return "brief";
  return section.repeat === "route" ? "route" : "stop";
};
const scopeAllows = (sectionScope, bindingScope) => bindingScope === "brief" || bindingScope === sectionScope;

const placeholders = (text) => [...text.matchAll(/\{([^{}]*)\}/g)].map((m) => m[1]);
const strayBraces = (text) => /[{}]/.test(text.replace(/\{[^{}]*\}/g, ""));

// Every problem found, so one run lists them all instead of stopping at the first.
const problemsOfBinding = (where, name, sectionScope) => {
  const binding = bindings[name];
  if (!binding) return [`${where}: no binding ${name}`];
  if (!scopeAllows(sectionScope, binding.scope)) return [`${where}: ${name} is ${binding.scope}-scoped, used in a ${sectionScope} section`];
  return [];
};

const problemsOfProcedureText = (where, procedure, text, sectionScope) => {
  const problems = strayBraces(text) ? [`${where}: a brace outside a placeholder`] : [];
  placeholders(text).forEach((p) => {
    const [kind, name] = p.split(/:(.*)/s);
    if (kind === "value") {
      if (!standards.values[name]) problems.push(`${where}: no standard value ${name}`);
    } else if (kind === "field") {
      if (!(procedure.fields || []).some((f) => f.id === name)) problems.push(`${where}: no field ${name}`);
    } else if (kind === "bind") {
      problems.push(...problemsOfBinding(where, name, sectionScope));
    } else {
      problems.push(`${where}: placeholder {${p}} is not value:, field: or bind:`);
    }
  });
  return problems;
};

const templateProblems = (file) => {
  const t = read(`${file}.json`);
  const problems = [];
  const say = (message) => problems.push(`${file}: ${message}`);

  if (t.format !== "ezpz-brief-template") say("format is not ezpz-brief-template");
  if (t.id !== file) say(`id ${t.id} is not its file's name`);
  if (!Number.isInteger(t.version) || t.version < 1) say("version is not a whole number from 1");
  ["title", "shortTitle", "source", "defaultWho"].forEach((k) => {
    if (typeof t[k] !== "string" || !t[k].trim()) say(`${k} is missing`);
  });
  if (!Array.isArray(t.paragraphs) || t.paragraphs.length === 0) {
    say("no paragraphs");
    return problems;
  }

  const sections = [];
  t.paragraphs.forEach((p, i) => {
    if (typeof p.number !== "string" || typeof p.title !== "string") say(`paragraph ${i + 1} has no number or title`);
    if (p.who !== undefined && (typeof p.who !== "string" || !p.who.trim())) say(`paragraph ${p.number}: who is empty`);
    if (!Array.isArray(p.sections) || p.sections.length === 0) say(`paragraph ${p.number} has no sections`);
    (p.sections || []).forEach((s) => sections.push(s));
  });

  const byId = new Map();
  sections.forEach((s) => {
    if (byId.has(s.id)) say(`section ${s.id} twice`);
    byId.set(s.id, s);
  });

  // Roles: every one named, each listing the sections repeated for it, and nothing repeated for a role it does not list.
  const roleKeys = Object.keys(t.roles || {});
  if (roleKeys.sort().join() !== [...ROLES].sort().join()) say(`roles are ${roleKeys.join(", ")}, not ${ROLES.join(", ")}`);
  ROLES.forEach((role) => {
    (t.roles?.[role] || []).forEach((id) => {
      const s = byId.get(id);
      if (!s) say(`role ${role} lists ${id}, which is no section`);
      else if (s.repeat !== role) say(`role ${role} lists ${id}, which repeats for ${s.repeat ?? "nothing"}`);
    });
  });
  sections.forEach((s) => {
    if (s.repeat === undefined || s.repeat === "route") return;
    if (!ROLES.includes(s.repeat)) say(`section ${s.id} repeats for ${s.repeat}, no role`);
    else if (!(t.roles?.[s.repeat] || []).includes(s.id)) say(`section ${s.id} repeats for ${s.repeat} but the role does not list it`);
  });

  // Steps: a step's sections are side by side and repeat alike, so one page and one sandtable step hold them.
  const seenSteps = new Map();
  sections.forEach((s, i) => {
    if (typeof s.step !== "string" || !s.step) {
      say(`section ${s.id} has no step`);
      return;
    }
    if (seenSteps.has(s.step)) {
      const last = seenSteps.get(s.step);
      if (last.index !== i - 1) say(`step ${s.step} is not side by side (section ${s.id})`);
      if (last.repeat !== s.repeat) say(`step ${s.step} mixes repeats (section ${s.id})`);
    }
    seenSteps.set(s.step, { index: i, repeat: s.repeat });
  });

  sections.forEach((s) => {
    const where = `section ${s.id}`;
    const scope = scopeOf(s);
    if (typeof s.title !== "string" || !s.title.trim()) say(`${where} has no title`);
    if (!FIGURES.includes(s.figure)) say(`${where}: figure ${s.figure} is unknown`);
    if (s.figure === "route" && scope !== "route") say(`${where}: a route figure in a section not repeated per route`);
    if (s.figure === "stop" && scope !== "stop") say(`${where}: a stop figure in a section not repeated per stop`);
    (s.facts || []).forEach((name) => problemsOfBinding(where, name, scope).forEach(say));

    const items = new Map();
    (s.items || []).forEach((item) => {
      const at = `${where}.${item.id}`;
      if (!ITEM_ID.test(item.id || "")) say(`${at}: id is not lower-case letters and dots`);
      if (items.has(item.id)) say(`${at} twice`);
      items.set(item.id, item);
      if (typeof item.label !== "string" || !item.label.trim()) say(`${at} has no label`);
      if (!KINDS.includes(item.kind)) say(`${at}: kind ${item.kind} is unknown`);
      if (item.kind === "choice" && !(Array.isArray(item.options) && item.options.length > 1)) say(`${at}: a choice needs options`);
      if (item.kind !== "choice" && item.options !== undefined) say(`${at}: options on a ${item.kind}`);
      if (item.standard !== undefined) {
        if (item.kind !== "procedure") say(`${at}: a standard on a ${item.kind}`);
        const procedure = standards.procedures[item.standard];
        if (!procedure) say(`${at}: no standard procedure ${item.standard}`);
        else {
          // A procedure's own placeholders must make sense where it is used.
          [procedure.summary, ...procedure.script].forEach((text) =>
            problemsOfProcedureText(`${at} (${item.standard})`, procedure, text, scope).forEach(say));
        }
      }
      if (item.bind !== undefined) {
        if (item.kind === "procedure" || item.kind === "info") say(`${at}: a ${item.kind} cannot be bound`);
        problemsOfBinding(at, item.bind, scope).forEach(say);
      }
    });
    if (items.size === 0) say(`${where} has no items`);

    if (!Array.isArray(s.script) || s.script.length === 0) say(`${where} has no script`);
    (s.script || []).forEach((sentence) => {
      if (typeof sentence !== "string" || !sentence.trim()) {
        say(`${where}: an empty sentence`);
        return;
      }
      if (strayBraces(sentence)) say(`${where}: a brace outside a placeholder in "${sentence}"`);
      placeholders(sentence).forEach((p) => {
        if (ITEM_ID.test(p)) {
          if (!items.has(p)) say(`${where}: {${p}} is no item of the section`);
          return;
        }
        const [kind, name] = p.split(/:(.*)/s);
        if (kind === "bind") problemsOfBinding(where, name, scope).forEach(say);
        else if (kind === "const") {
          if (!constants.some((c) => c.id === name)) say(`${where}: no constant ${name}`);
        } else if (kind === "item") {
          const dot = name.lastIndexOf(".");
          const other = byId.get(name.slice(0, dot));
          // An item of a repeated section has one value per stop or route, so only its own section may name it.
          if (!other || !(other.items || []).some((it) => it.id === name.slice(dot + 1))) say(`${where}: {${p}} names no item`);
          else if (other.repeat !== undefined && other !== s) say(`${where}: {${p}} names an item of a repeated section`);
        } else say(`${where}: placeholder {${p}} is not an item, bind:, const: or item:`);
      });
    });
    // Every item is said somewhere in its section's script, so nothing filled in is left out of the brief.
    items.forEach((item, id) => {
      if (item.kind === "info") return;
      if (!(s.script || []).some((sentence) => placeholders(sentence).includes(id))) say(`${where}.${id} is in no sentence of the script`);
    });
  });

  return problems;
};

describe("mission brief templates", () => {
  it.each(TEMPLATES)("%s keeps every rule of the format", (file) => {
    expect(templateProblems(file)).toEqual([]);
  });

  it("every file in contracts/briefs is accounted for", () => {
    const files = fs.readdirSync(BRIEFS).filter((f) => f.endsWith(".json")).sort();
    expect(files).toEqual([...TEMPLATES.map((t) => `${t}.json`), "bindings.json", "constants.json", "standards.json"].sort());
  });

  it("names every binding with a scope, a type and a description", () => {
    const problems = [];
    Object.entries(bindings).forEach(([name, b]) => {
      if (!/^[a-z]+(\.[a-zA-Z]+)?$/.test(name)) problems.push(`${name}: not a dotted lower camel name`);
      if (!SCOPES.includes(b.scope)) problems.push(`${name}: scope ${b.scope}`);
      if (!TYPES.includes(b.type)) problems.push(`${name}: type ${b.type}`);
      if (typeof b.description !== "string" || b.description.length < 10) problems.push(`${name}: no description`);
      if (b.scope !== "brief" && !name.startsWith(`${b.scope}.`)) problems.push(`${name}: a ${b.scope} binding is named ${b.scope}.…`);
      if (b.scope === "brief" && /^(stop|route)\./.test(name)) problems.push(`${name}: named like a ${name.split(".")[0]} binding`);
    });
    expect(problems).toEqual([]);
  });

  it("gives every constant a label, a band name, and a source that exists", () => {
    const problems = [];
    const ids = new Set();
    constants.forEach((c) => {
      if (ids.has(c.id)) problems.push(`${c.id} twice`);
      ids.add(c.id);
      if (typeof c.label !== "string" || !c.label.trim()) problems.push(`${c.id}: no label`);
      if (typeof c.short !== "string" || c.short !== c.short.toUpperCase() || c.short.length > 12) problems.push(`${c.id}: short is not upper case, 12 at most`);
      if (typeof c.offered !== "boolean") problems.push(`${c.id}: offered is not true or false`);
      if (c.bind !== undefined && bindings[c.bind]?.scope !== "brief") problems.push(`${c.id}: bind ${c.bind} is no brief binding`);
      if (c.standard !== undefined && !standards.values[c.standard]) problems.push(`${c.id}: no standard value ${c.standard}`);
    });
    expect(problems).toEqual([]);
    expect(constants.filter((c) => c.offered).length).toBeGreaterThan(0);
  });

  it("gives every standard a label and every procedure a title, a summary and a script", () => {
    const problems = [];
    expect(standards.format).toBe("ezpz-brief-standards");
    Object.entries(standards.values).forEach(([id, v]) => {
      if (v.value === undefined || v.value === null || v.value === "") problems.push(`${id}: no value`);
      if (typeof v.label !== "string" || !v.label.trim()) problems.push(`${id}: no label`);
    });
    Object.entries(standards.procedures).forEach(([id, p]) => {
      if (typeof p.title !== "string" || typeof p.summary !== "string") problems.push(`${id}: no title or summary`);
      if (!Array.isArray(p.script) || p.script.length === 0) problems.push(`${id}: no script`);
      const fieldIds = (p.fields || []).map((f) => f.id);
      if (new Set(fieldIds).size !== fieldIds.length) problems.push(`${id}: a field twice`);
      // Brief-scoped text here is checked in full; stop and route bindings are checked where a template uses the procedure.
      [p.summary, ...(p.script || [])].forEach((text) =>
        problemsOfProcedureText(id, p, text, "stop").concat(problemsOfProcedureText(id, p, text, "route"))
          .filter((m) => !/-scoped, used in a/.test(m))
          .forEach((m) => problems.includes(m) || problems.push(m)));
    });
    expect(problems).toEqual([]);
  });

  // The checks themselves: a template broken on purpose must be caught, or the test above proves nothing.
  describe("catches a broken template", () => {
    const original = fs.readFileSync(path.join(BRIEFS, "amb.json"), "utf8");
    const broken = (edit) => {
      const t = JSON.parse(original);
      edit(t);
      const spy = jest.spyOn(fs, "readFileSync").mockImplementation((p, ...rest) =>
        String(p).endsWith(`${path.sep}amb.json`) ? JSON.stringify(t) : jest.requireActual("fs").readFileSync(p, ...rest));
      try {
        return templateProblems("amb");
      } finally {
        spy.mockRestore();
      }
    };
    const sectionOf = (t, id) => t.paragraphs.flatMap((p) => p.sections).find((s) => s.id === id);

    it.each([
      ["a placeholder naming no item", (t) => sectionOf(t, "1").script.push("Oops {z}."), /\{z\} is no item/],
      ["an unknown binding", (t) => { sectionOf(t, "1").items[0].bind = "packet.nothing"; }, /no binding packet\.nothing/],
      ["a stop binding outside a stop section", (t) => sectionOf(t, "1").script.push("{bind:stop.grid}"), /stop-scoped, used in a brief section/],
      ["a route binding in a stop section", (t) => sectionOf(t, "4.e").script.push("{bind:route.name}"), /route-scoped, used in a stop section/],
      ["an item no sentence says", (t) => sectionOf(t, "1").items.push({ id: "c", label: "Extra", kind: "text" }), /1\.c is in no sentence/],
      ["a role without its section", (t) => { t.roles.LZ = []; }, /repeats for LZ but the role does not list it/],
      ["a role listing the wrong section", (t) => { t.roles.PZ = ["4.e"]; }, /role PZ lists 4\.e, which repeats for LZ/],
      ["a missing role", (t) => { delete t.roles.LAAGER; }, /roles are/],
      ["a step split in two", (t) => { sectionOf(t, "3").step = "1"; }, /step 1 is not side by side/],
      ["an unknown standard", (t) => { sectionOf(t, "5.a").items[0].standard = "nothing"; }, /no standard procedure nothing/],
      ["a stray brace", (t) => sectionOf(t, "1").script.push("Half {open"), /a brace outside a placeholder/],
      ["an item of a repeated section named elsewhere", (t) => sectionOf(t, "7").script.push("{item:4.e.ii}"), /names an item of a repeated section/],
      ["a stop figure in a brief section", (t) => { sectionOf(t, "2").figure = "stop"; }, /a stop figure in a section not repeated per stop/],
      ["a choice with no options", (t) => { delete sectionOf(t, "1").items[1].options; }, /a choice needs options/],
    ])("%s", (_name, edit, expected) => {
      expect(broken(edit).join("\n")).toMatch(expected);
    });
  });
});
