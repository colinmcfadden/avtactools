/**
 * compileBrief: a format, the person's answers and the plan in, everything the brief says out.
 *
 * The one place a brief's content is decided (docs/MISSION_BRIEF.md §2.3). The PDF and the sandtable only lay out
 * what this returns, so a step says the same thing on the sandtable as in the PDF. Pure and
 * deterministic: no clock (the generation time is an input), no network, no random ids. Held to
 * contracts/fixtures/brief/compile.json; Android's core-brief is a port of this file held to the same fixture.
 *
 * Times. A route's clock times are wall-clock times on the plan's date, as the route planner keeps them; the brief
 * prints them as they are and treats them as local at `context.utcOffsetMinutes`, the offset the light data is
 * worked out for. Nothing here reads the device's zone: a wall-clock time is carried as the milliseconds of its
 * fields read as UTC ("wall" below), and only made an instant with the offset.
 */
import { normalizeLzDiagram } from "../lzWorkspace/useLzWorkspace";
import { computeRoutePlan, distanceNm, ensureRoutePlan, planPoints, trueCourseDeg } from "../msnxImport/routeCalc";
import { landingHeadingOf, slopeCallFor, slopeWindOf } from "../terrain/slopeCall";
import { findProfile } from "../aircraft/aircraftProfiles";
import { toMgrs } from "../../utils/mgrs";
import { lightData } from "./light";
import { ROLE_NAMES, ROLES } from "./templates";

export const BRIEF_MODEL_FORMAT = "ezpz-brief-model";
export const BRIEF_MODEL_VERSION = 1;

/** How a write-in line is shown where a brief is read as text (the sandtable, a preview). */
export const WRITE_IN = "________";

/** A stop is at a route point within this distance of its target. */
const STOP_MATCH_M = 500;
const METRES_PER_NM = 1852;
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const COMPASS = ["north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"];

// ---------------------------------------------------------------------------------------------------------------
// Words and numbers. Formatted by hand, never by the locale (a device's can write other digits).

const pad2 = (n) => String(n).padStart(2, "0");
const withCommas = (n) => {
  const whole = String(Math.abs(Math.round(n)));
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return Math.round(n) < 0 ? `-${grouped}` : grouped;
};
const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;
const nonBlank = (v) => (typeof v === "string" ? v.trim() !== "" : v !== null && v !== undefined);
const isObject = (v) => v !== null && typeof v === "object" && !Array.isArray(v);
const num = (v) => (typeof v === "number" && Number.isFinite(v) ? v : null);

/** One leading dot of an AMPS name is not said (".RP" is RP). */
const pointName = (name, fallback) => {
  const text = typeof name === "string" ? name.trim() : "";
  return text ? text.replace(/^\./, "") : fallback;
};

const headingText = (deg) => `${String(((Math.round(deg) % 360) + 360) % 360).padStart(3, "0")}°`;
const feetText = (ft) => `${withCommas(ft)} ft`;
const nmText = (nm) => `${nm.toFixed(1)} nm`;
const fuelText = (lb) => `${withCommas(lb)} lb`;
const percentText = (fraction) => `${Math.round(fraction * 100)}%`;

const airspeedText = (airspeed) => {
  const value = num(airspeed?.value);
  if (value === null) return null;
  if (airspeed.type === "indicated") return `${value} KIAS`;
  if (airspeed.type === "true") return `${value} KTAS`;
  return `${value} kt GS`;
};

const altitudeText = (altitude) => {
  const value = num(altitude?.value);
  if (value === null) return null;
  return `${withCommas(value)} ft ${altitude.ref === "msl" ? "MSL" : "AGL"}`;
};

/** Seconds as h+mm+ss, as AMPS writes a time enroute. */
const durationText = (sec) => {
  if (sec === null || sec === undefined) return null;
  const s = Math.round(sec);
  return `${Math.floor(s / 3600)}+${pad2(Math.floor((s % 3600) / 60))}+${pad2(s % 60)}`;
};

/** A route planner clock (a Date on the device) as wall-clock milliseconds: its fields read as UTC. */
const wallOf = (date) =>
  date instanceof Date && !Number.isNaN(date.getTime())
    ? Date.UTC(date.getFullYear(), date.getMonth(), date.getDate(), date.getHours(), date.getMinutes(), date.getSeconds())
    : null;

const clockText = (wall) => {
  if (wall === null) return null;
  const d = new Date(wall);
  return `${pad2(d.getUTCHours())}:${pad2(d.getUTCMinutes())}:${pad2(d.getUTCSeconds())}`;
};

/** Hours and minutes of a wall clock, the seconds dropped (a time worked out from another, not planned). */
const minuteText = (wall) => {
  if (wall === null) return null;
  const d = new Date(wall);
  return `${pad2(d.getUTCHours())}:${pad2(d.getUTCMinutes())}`;
};

/** An instant as local hours and minutes at the offset, to the nearest minute. */
const instantMinuteText = (ms, offsetMinutes) => {
  if (ms === null || ms === undefined) return null;
  const local = Math.round((ms + offsetMinutes * 60000) / 60000) * 60000;
  return minuteText(local);
};

const dateText = (isoDate) => {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(isoDate || "");
  return m ? `${Number(m[3])} ${MONTHS[Number(m[2]) - 1]} ${m[1]}` : null;
};

const compassOf = (deg) => COMPASS[Math.round((((deg % 360) + 360) % 360) / 45) % 8];

const metresBetween = (a, b) => distanceNm(a.lat, a.lon, b.lat, b.lon) * METRES_PER_NM;

// ---------------------------------------------------------------------------------------------------------------
// The plan, as the brief reads it.

const defaultRole = (name) => {
  const text = String(name || "").trim().toUpperCase();
  if (/^PZ\b/.test(text)) return "PZ";
  if (/^FARP\b/.test(text)) return "FARP";
  if (/^(HA|HOLDING)\b/.test(text)) return "HA";
  if (/^LAAGER\b/.test(text)) return "LAAGER";
  return "LZ";
};

/** A stop's name with its role in front, unless the name already starts with it ("LZ HAWK", not "LZ LZ HAWK"). */
const namedWithRole = (stop) => {
  const role = ROLE_NAMES[stop.role] || stop.role;
  return stop.name.toUpperCase().startsWith(`${String(role).toUpperCase()} `) ? stop.name : `${role} ${stop.name}`;
};

const readStop = (entry) => {
  const diagram = normalizeLzDiagram(entry?.diagram || {}, { createdAt: "", updatedAt: "" });
  const target = diagram.target;
  const name = String(entry?.name ?? diagram.name ?? "").trim() || "LZ/PZ";
  return { kind: "stop", ref: `lz:${entry.id}`, id: String(entry.id), name, diagram, target };
};

const readRoutes = (set, missionDate) => {
  const routes = Array.isArray(set?.data?.routes) ? set.data.routes : [];
  return routes
    .filter((r) => isObject(r) && r.id !== undefined && Array.isArray(r.points))
    .map((raw) => {
      const route = ensureRoutePlan(raw);
      const plan = { ...route.plan, date: route.plan.date || missionDate };
      const result = computeRoutePlan(route, plan, isObject(raw.elevations) ? raw.elevations : {});
      return {
        kind: "route",
        ref: `route:${set.id}/${raw.id}`,
        id: String(raw.id),
        setId: String(set.id),
        name: String(raw.name || "").trim() || "ROUTE",
        route,
        plan,
        result,
        named: planPoints(route),
      };
    });
};

/** The local date (YYYY-MM-DD) of an instant at an offset. */
const localDateOf = (iso, offsetMinutes) => {
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms)) return null;
  return new Date(ms + offsetMinutes * 60000).toISOString().slice(0, 10);
};

/**
 * The flow: the person's order if they gave one, else each route followed by the LZ/PZs at its points (in the
 * order it reaches them), then any LZ/PZ no route reaches. An entry that names nothing in the plan stays, marked
 * gone, so setup can say so instead of dropping it.
 */
const resolveFlow = (documentFlow, stops, routes, problems) => {
  const byRef = new Map([...stops, ...routes].map((x) => [x.ref, x]));
  if (Array.isArray(documentFlow) && documentFlow.length > 0) {
    return documentFlow
      .filter((f) => isObject(f) && typeof f.ref === "string")
      .map((f) => {
        const item = byRef.get(f.ref);
        if (!item) {
          problems.push({ ref: f.ref, message: "This item is no longer in the plan." });
          return { ref: f.ref, gone: true };
        }
        if (item.kind === "route") return { ...item };
        const role = ROLES.includes(f.role) ? f.role : defaultRole(item.name);
        return { ...item, role };
      });
  }
  const flow = [];
  const used = new Set();
  routes.forEach((route) => {
    flow.push({ ...route });
    const reached = [];
    stops.forEach((stop) => {
      if (used.has(stop.ref) || !stop.target) return;
      const index = route.named.findIndex((p) => metresBetween(p, stop.target) <= STOP_MATCH_M);
      if (index >= 0) reached.push({ stop, index });
    });
    reached
      .sort((a, b) => a.index - b.index)
      .forEach(({ stop }) => {
        used.add(stop.ref);
        flow.push({ ...stop, role: defaultRole(stop.name) });
      });
  });
  stops.forEach((stop) => {
    if (!used.has(stop.ref)) flow.push({ ...stop, role: defaultRole(stop.name) });
  });
  return flow;
};

/** The named point of a route nearest a stop's target, within reach, with its plan row; else null. */
const routePointAt = (route, target) => {
  if (!target || !route.result?.points?.length) return null;
  let best = null;
  route.result.points.forEach((p, index) => {
    const d = metresBetween(p, target);
    if (d <= STOP_MATCH_M && (best === null || d < best.d)) best = { point: p, index, d };
  });
  return best;
};

// ---------------------------------------------------------------------------------------------------------------
// Bindings: every value bindings.json promises, worked out once for the brief, once per stop and once per route.

const weatherText = (report) => {
  if (!isObject(report)) return null;
  const station = typeof report.station_id === "string" ? report.station_id.trim() : "";
  if (!station || station === "TIMEOUT") return null;
  const raw = [report.raw_text, report.raw_taf, report.raw_metar].find((r) => typeof r === "string" && r.trim());
  if (!raw) return null;
  return report.kind === "forecast" ? `Forecast ${raw.trim()}` : raw.trim();
};

const notamsText = (notams) => {
  if (typeof notams === "string") return "NOTAMs could not be searched: check them before flight.";
  if (!isObject(notams)) return null;
  const kinds = Object.entries(notams).filter(([kind, list]) => kind !== "Clear" && Array.isArray(list) && list.length > 0);
  if (kinds.length === 0) return "None in the area.";
  const total = kinds.reduce((sum, [, list]) => sum + list.length, 0);
  return `${plural(total, "NOTAM", "NOTAMs")}: ${kinds.map(([kind, list]) => `${list.length} ${kind.toLowerCase()}`).join(", ")}.`;
};

/** A route's legs grouped where a value changes: "120 KIAS (3MILE to S-EP), 100 KIAS (S-EP to RP1)". */
const changesText = (points, valueOf) => {
  const runs = [];
  points.forEach((p, i) => {
    if (i === 0) return;
    const value = valueOf(p, i);
    const last = runs[runs.length - 1];
    if (last && last.value === value) last.to = p;
    else runs.push({ value, from: points[i - 1], to: p });
  });
  const said = runs.filter((r) => r.value !== null);
  if (said.length === 0) return null;
  if (said.length === 1 && runs.length === 1) return said[0].value;
  return said.map((r) => `${r.value} (${pointName(r.from.name, "start")} to ${pointName(r.to.name, "end")})`).join(", ");
};

/** Leaving along a route from its point [from] (its start unless said): name, takeoff, heading, altitude, airspeed. */
const departurePlanText = (route, from = 0) => {
  const first = route?.result?.points?.[from];
  const second = route?.result?.points?.[from + 1];
  if (!first || !second) return null;
  const parts = [from > 0 ? `${route.name} from ${pointName(first.name, "here")}` : route.name];
  const start = clockText(wallOf(first.clockTime));
  if (start) parts.push(`takeoff ${start}`);
  parts.push(`heading ${headingText(second.legCourseTrueDeg)}`);
  const alt = altitudeText(first);
  if (alt) parts.push(alt);
  const speed = airspeedText(second.airspeed);
  if (speed) parts.push(speed);
  return parts.join(", ");
};

const goAroundSide = (diagram) => {
  const side = String(diagram?.graphics?.goArounds?.[0]?.side ?? "").trim().toUpperCase();
  if (side === "L" || side === "LEFT") return "left";
  if (side === "R" || side === "RIGHT") return "right";
  return null;
};

const aircraftText = (helicopters, profiles, fallbackSlug) => {
  if (!Array.isArray(helicopters) || helicopters.length === 0) return null;
  const counts = new Map();
  helicopters.forEach((h) => {
    const slug = h?.profileId || fallbackSlug || "uh60l";
    counts.set(slug, (counts.get(slug) || 0) + 1);
  });
  return [...counts.entries()]
    .map(([slug, n]) => {
      const profile = findProfile(profiles, slug);
      return `${n} × ${profile?.designation || profile?.name || String(slug).toUpperCase()}`;
    })
    .join(", ");
};

/** The airspeed covering the most distance across the routes, if any leg has one. */
const enrouteAirspeed = (routes) => {
  const byText = new Map();
  routes.forEach((r) =>
    (r.result?.legs || []).forEach((leg) => {
      const text = airspeedText(leg.airspeed);
      if (text) byText.set(text, (byText.get(text) || 0) + leg.distNm);
    }));
  let best = null;
  byText.forEach((dist, text) => {
    if (best === null || dist > best.dist) best = { text, dist };
  });
  return best ? best.text : null;
};

const routeBindings = (route, problems) => {
  const { result } = route;
  if (!result || result.points.length < 2) {
    problems.push({ ref: route.ref, message: `${route.name} needs at least two route points to be briefed.` });
  }
  const points = result?.points || [];
  const first = points[0];
  const last = points[points.length - 1];
  const named = points.map((p) => pointName(p.name, "PT"));
  return {
    "route.name": route.name,
    "route.summary": points.length >= 2
      ? `${route.name}, ${named[0]} to ${named[named.length - 1]}, ${nmText(result.totals.distNm)}${result.totals.timeSec !== null ? `, ${durationText(result.totals.timeSec)}` : ""}`
      : null,
    "route.overview": points.length >= 2
      ? points.slice(1).map((p, i) =>
        `${named[i]} to ${named[i + 1]} ${headingText(p.legCourseTrueDeg)} ${nmText(p.legDistNm)}${p.legTimeSec !== null ? ` ${durationText(p.legTimeSec)}` : ""}`).join("; ")
      : null,
    "route.start": first ? clockText(wallOf(first.clockTime)) : null,
    "route.end": last ? clockText(wallOf(last.clockTime)) : null,
    "route.airspeed": changesText(points, (p) => airspeedText(p.airspeed)),
    "route.altitude": changesText(points, (p) => altitudeText(p)),
    "route.distance": result?.totals ? nmText(result.totals.distNm) : null,
    "route.time": result?.totals ? durationText(result.totals.timeSec) : null,
    "route.checkpoints": points.length > 0 ? points.map((p, i) => `${named[i]}${p.ptType ? ` (${p.ptType})` : ""}`) : null,
    "route.fuel": result?.totals?.fuelLb !== null && result?.totals?.fuelLb !== undefined ? fuelText(result.totals.fuelLb) : null,
  };
};

const stopBindings = (stop, flow, context, plan) => {
  const { diagram, target } = stop;
  const index = flow.indexOf(stop);
  const before = flow.slice(0, index).reverse().find((f) => f.kind === "route");
  const after = flow.slice(index + 1).find((f) => f.kind === "route");
  const at = before ? routePointAt(before, target) : null;
  const rawElevation = nonBlank(diagram.analysis?.gridElevation) ? diagram.analysis.gridElevation : diagram.analysis?.results?.elevation;
  const elevation = nonBlank(rawElevation) && String(rawElevation).trim() !== "" ? Number(rawElevation) : Number.NaN;

  let inbound = null;
  if (at && at.index > 0) {
    const rp = before.result.points[at.index - 1];
    const here = at.point;
    const parts = [`from ${pointName(rp.name, "the RP")}`];
    const rpTime = clockText(wallOf(rp.clockTime));
    if (rpTime) parts[0] += ` at ${rpTime}`;
    const speed = airspeedText(here.airspeed);
    if (speed) parts.push(speed);
    parts.push(nmText(here.legDistNm));
    if (here.legTimeSec !== null) parts.push(durationText(here.legTimeSec));
    inbound = parts.join(", ");
  }

  const landing = landingHeadingOf(diagram.flightData?.landing_hdg);
  const takeoff = landingHeadingOf(diagram.flightData?.takeoff_hdg);
  const report = isObject(context.weather?.stops) ? context.weather.stops[stop.id] : null;
  let slope = null;
  if (diagram.analysis?.terrainData?.stats) {
    const call = slopeCallFor(diagram.analysis.terrainData, { landingHdg: diagram.flightData?.landing_hdg, wind: slopeWindOf(report) });
    slope = [call.label, ...call.lines].filter(Boolean).join(". ");
  }

  return {
    "stop.name": stop.name,
    "stop.role": ROLE_NAMES[stop.role] || stop.role,
    "stop.grid": target ? (nonBlank(target.mgrs) ? target.mgrs.trim() : toMgrs(target.lat, target.lon)) : null,
    "stop.elevation": Number.isFinite(elevation) ? `${feetText(elevation)} MSL` : null,
    "stop.landingHeading": landing !== null ? headingText(landing) : null,
    "stop.takeoffHeading": takeoff !== null ? headingText(takeoff) : null,
    "stop.goAround": goAroundSide(diagram),
    "stop.aircraft": aircraftText(diagram.graphics?.helicopters, plan.aircraftProfiles, plan.missionAircraft),
    "stop.arrive": at ? clockText(wallOf(at.point.clockTime)) : null,
    "stop.inbound": inbound,
    // On along the route that brought it, if that route carries on; else the next route in the flow.
    "stop.departurePlan": at && at.index < before.result.points.length - 1
      ? departurePlanText(before, at.index)
      : after ? departurePlanText(after) : null,
    "stop.slope": slope,
    "stop.weather": weatherText(report),
  };
};

const threatsText = (threats, routes, include) => {
  if (include === false) return "Not briefed.";
  const list = Array.isArray(threats) ? threats.filter((t) => isObject(t) && num(t.lat) !== null && num(t.lon) !== null) : [];
  if (list.length === 0) return "None in the plan.";
  return `${list.map((t) => {
    const name = String(t.name ?? t.label ?? "").trim() || "Unnamed threat";
    let nearest = null;
    routes.forEach((r) => (r.result?.points || []).forEach((p) => {
      const d = distanceNm(p.lat, p.lon, t.lat, t.lon);
      if (nearest === null || d < nearest.d) nearest = { p, d };
    }));
    if (!nearest) return name;
    const bearing = trueCourseDeg(nearest.p.lat, nearest.p.lon, t.lat, t.lon);
    return `${name}, ${nmText(nearest.d)} ${compassOf(bearing)} of ${pointName(nearest.p.name, "the route")}`;
  }).join("; ")}.`;
};

const briefBindings = ({ plan, document, context, flow, routes, stops, missionDate, offset }) => {
  const flowRoutes = flow.filter((f) => f.kind === "route");
  const flowStops = flow.filter((f) => f.kind === "stop");
  const firstRoute = flowRoutes[0] || null;
  const lastRoute = flowRoutes[flowRoutes.length - 1] || null;
  const firstPoint = firstRoute?.result?.points?.[0] || null;
  const lastPoint = lastRoute?.result?.points?.[lastRoute.result.points.length - 1] || null;
  const takeoffWall = firstPoint ? wallOf(firstPoint.clockTime) : null;
  const firstStop = flowStops[0] || null;
  const departAt = firstPoint || (firstStop?.target ? { lat: firstStop.target.lat, lon: firstStop.target.lon } : null);
  // Where the flight leaves from is a route's first point: a flow that starts at an LZ/PZ with no route has none.
  const departureName = firstPoint ? pointName(firstPoint.name, "the start") : null;

  // The aircraft: the most placed on any LZ/PZ in the flow.
  let aircraft = null;
  let mostPlaced = 0;
  flowStops.forEach((s) => {
    const n = s.diagram.graphics?.helicopters?.length || 0;
    if (n > mostPlaced) {
      mostPlaced = n;
      aircraft = aircraftText(s.diagram.graphics.helicopters, plan.aircraftProfiles, plan.missionAircraft);
    }
  });

  const light = departAt
    ? lightData({
      lat: departAt.lat, lon: departAt.lon, date: missionDate, utcOffsetMinutes: offset,
      at: takeoffWall !== null ? takeoffWall - offset * 60000 : null,
    })
    : null;
  const event = (key) => (light ? (light[key] !== null ? instantMinuteText(light[key], offset) : "none that day") : null);

  const standards = context.standards;
  const minutesBefore = (id) => (takeoffWall !== null ? minuteText(takeoffWall - standards.values[id].value * 60000) : null);

  const fuel = flowRoutes.reduce((sum, r) => (sum === null || r.result?.totals?.fuelLb == null ? null : sum + r.result.totals.fuelLb), flowRoutes.length ? 0 : null);
  const distance = flowRoutes.reduce((sum, r) => sum + (r.result?.totals?.distNm || 0), 0);
  const time = flowRoutes.reduce((sum, r) => (sum === null || r.result?.totals?.timeSec == null ? null : sum + r.result.totals.timeSec), flowRoutes.length ? 0 : null);

  const winds = new Map();
  flowRoutes.forEach((r) => (r.result?.legs || []).forEach((leg) => {
    const spd = num(leg.wind?.speedKts);
    if (spd === null || spd <= 0) return;
    const text = `${headingText(leg.wind.dirTrue ?? 0).replace("°", "")}/${Math.round(spd)} kt`;
    winds.set(text, (winds.get(text) || 0) + leg.distNm);
  }));
  let enrouteWind = null;
  winds.forEach((d, text) => { if (enrouteWind === null || d > enrouteWind.d) enrouteWind = { text, d }; });

  const departureWx = weatherText(context.weather?.departure);
  const enrouteWx = enrouteWind ? `Winds ${enrouteWind.text}` : (nonBlank(context.weather?.enroute) ? String(context.weather.enroute).trim() : null);
  const arrivalWx = weatherText(context.weather?.arrival);
  const wxParts = [["Takeoff", departureWx], ["Enroute", enrouteWx], ["Arrival", arrivalWx]].filter(([, v]) => v);

  const pressure = num(context.weather?.departure?.pressure);

  // RP inbound: the last leg into each stop a route reaches, when all agree.
  const inboundSpeeds = new Set();
  flowStops.forEach((s) => {
    const index = flow.indexOf(s);
    const before = flow.slice(0, index).reverse().find((f) => f.kind === "route");
    const at = before ? routePointAt(before, s.target) : null;
    if (at && at.index > 0) inboundSpeeds.add(airspeedText(at.point.airspeed));
  });
  const goArounds = new Set(flowStops.map((s) => goAroundSide(s.diagram)).filter(Boolean));

  const rally = (Array.isArray(document.rallyPoints) ? document.rallyPoints : [])
    .map((r) => {
      const route = routes.find((x) => x.ref === r?.ref);
      const point = route?.named.find((p) => p.id === r?.pointId);
      if (!point) return null;
      return `${pointName(point.name, "a point")}${r.label === "inbound" || r.label === "outbound" ? ` (${r.label})` : ""}`;
    })
    .filter(Boolean);

  const timeline = flow.filter((f) => !f.gone).map((f) => {
    if (f.kind === "route") {
      const start = clockText(wallOf(f.result?.points?.[0]?.clockTime));
      return `${start ? `${start} ` : ""}${f.name}`;
    }
    const before = flow.slice(0, flow.indexOf(f)).reverse().find((x) => x.kind === "route");
    const at = before ? routePointAt(before, f.target) : null;
    const arrive = at ? clockText(wallOf(at.point.clockTime)) : null;
    return `${arrive ? `${arrive} ` : ""}${namedWithRole(f)}`;
  });

  // The whole mission in a sentence.
  const roleWords = { LZ: "to", PZ: "pickup at", FARP: "fuel at", HA: "holding at", LAAGER: "laager at", FALSE_LZ: "false insertion at" };
  const groups = [];
  flowStops.forEach((s) => {
    const last = groups[groups.length - 1];
    if (last && last.role === s.role) last.names.push(s.name);
    else groups.push({ role: s.role, names: [s.name] });
  });
  const joinNames = (names) => (names.length <= 1 ? names.join("") : `${names.slice(0, -1).join(", ")} and ${names[names.length - 1]}`);
  const summaryParts = [aircraft || "the flight"];
  if (departureName) summaryParts.push(`from ${departureName}`);
  const groupWords = groups.map((g) => `${roleWords[g.role] || "at"} ${joinNames(g.names)}`);
  let summary = [[summaryParts.join(" "), groupWords[0]].filter(Boolean).join(" "), ...groupWords.slice(1)].join(", ");
  const endWall = lastPoint ? wallOf(lastPoint.clockTime) : null;
  if (lastPoint) summary += `, back at ${pointName(lastPoint.name, "the end")}${endWall !== null ? ` at ${clockText(endWall)}` : ""}`;
  summary += ".";

  return {
    "mission.name": String(plan.name || "").trim() || "the mission",
    "mission.date": dateText(missionDate),
    "mission.distance": flowRoutes.length ? nmText(distance) : null,
    "mission.flightTime": time !== null ? durationText(time) : null,
    "flight.aircraft": aircraft,
    "flight.summary": summary,
    "weather.summary": wxParts.length ? `${wxParts.map(([k, v]) => `${k}: ${v}`).join(". ")}.` : null,
    "weather.departure": departureWx,
    "weather.enroute": enrouteWx,
    "weather.arrival": arrivalWx,
    "weather.altimeter": pressure !== null ? `${pressure.toFixed(2)} inHg` : null,
    "light.bmnt": event("bmnt"),
    "light.sunrise": event("sunrise"),
    "light.sunset": event("sunset"),
    "light.eent": event("eent"),
    "light.moonrise": event("moonrise"),
    "light.moonset": event("moonset"),
    "light.illumination": light ? percentText(light.illumination) : null,
    "light.summary": light
      ? `BMNT ${event("bmnt")}, sunrise ${event("sunrise")}, sunset ${event("sunset")}, EENT ${event("eent")}, moonrise ${event("moonrise")}, moonset ${event("moonset")}, illumination ${percentText(light.illumination)}.`
      : null,
    "notams.summary": notamsText(context.notams ?? context.weather?.departure?.notams),
    "threats.summary": threatsText(plan.threats, flowRoutes, document.include?.threats),
    timeline: timeline.length ? timeline : null,
    "departure.point": departureName,
    "departure.time": clockText(takeoffWall),
    "departure.commoCheck": minutesBefore("commoCheckMinutes"),
    "departure.taxi": minutesBefore("taxiLoadMinutes"),
    "departure.lineup": minutesBefore("lineupMinutes"),
    "departure.fuel": fuel !== null ? fuelText(fuel) : null,
    "departure.plan": firstRoute ? departurePlanText(firstRoute) : null,
    "arrival.point": lastPoint ? pointName(lastPoint.name, "the end") : flowStops[flowStops.length - 1]?.name ?? null,
    "arrival.time": clockText(endWall),
    "routes.enrouteAirspeed": enrouteAirspeed(flowRoutes),
    "routes.rpInboundAirspeed": inboundSpeeds.size === 1 && !inboundSpeeds.has(null) ? [...inboundSpeeds][0] : null,
    "stops.goAround": goArounds.size === 1 ? [...goArounds][0] : null,
    "rally.points": rally.length ? rally.join(", ") : null,
  };
};

// ---------------------------------------------------------------------------------------------------------------
// The template: steps, items, scripts.

const sectionsOf = (template) =>
  template.paragraphs.flatMap((p) => p.sections.map((s) => ({ ...s, paragraph: p })));

/** Side-by-side sections that share a step and a repeat are one step. */
const stepGroups = (template) => {
  const groups = [];
  sectionsOf(template).forEach((s) => {
    const last = groups[groups.length - 1];
    if (last && last.step === s.step && last.repeat === s.repeat) last.sections.push(s);
    else groups.push({ step: s.step, repeat: s.repeat, sections: [s] });
  });
  return groups;
};

const repeatsFor = (group, entry) =>
  entry.kind === "route" ? group.repeat === "route" : group.repeat === entry.role;

/**
 * The steps, in the order they are briefed: each group of the template in turn, except that a run of repeated
 * groups is briefed in flow order (departure, the first route, the LZs it reaches, the next route...), as the
 * flight meets them.
 */
const planSteps = (template, flow) => {
  const groups = stepGroups(template);
  const steps = [];
  for (let i = 0; i < groups.length; ) {
    if (groups[i].repeat === undefined) {
      steps.push({ group: groups[i], entry: null });
      i += 1;
      continue;
    }
    const run = [];
    while (i < groups.length && groups[i].repeat !== undefined) run.push(groups[i++]);
    flow.filter((f) => !f.gone).forEach((entry) =>
      run.filter((g) => repeatsFor(g, entry)).forEach((group) => steps.push({ group, entry })));
  }
  return steps;
};

const stepIdOf = ({ group, entry }) => (entry ? `${group.step}@${entry.ref}` : group.step);
const answerKey = (section, item, entry) => `${section.id}.${item.id}${entry ? `@${entry.ref}` : ""}`;

const textPart = (text) => ({ text });
/** A line to write on, with what it is for (an item's label, a binding's or a field's), so a page can say so. */
const writeInPart = (name, label) => ({ writeIn: name, label: label ?? null });

/** Joins parts, merging neighbouring text, so a sentence is as few parts as it can be. */
const tidy = (parts) =>
  parts.reduce((out, p) => {
    const last = out[out.length - 1];
    if (p.text !== undefined && last?.text !== undefined) last.text += p.text;
    else if (p.text !== "") out.push({ ...p });
    return out;
  }, []);

/** A sentence with placeholders as parts, each placeholder through `resolve` (parts, or null for a write-in). */
const fill = (sentence, resolve, labelOf = () => null) => {
  const parts = [];
  let last = 0;
  sentence.replace(/\{([^{}]*)\}/g, (match, name, offset) => {
    parts.push(textPart(sentence.slice(last, offset)));
    const value = resolve(name);
    if (value === null || value === undefined) parts.push(writeInPart(name, labelOf(name)));
    else parts.push(...value);
    last = offset + match.length;
    return match;
  });
  parts.push(textPart(sentence.slice(last)));
  return tidy(parts);
};

const valueParts = (value) => {
  if (value === null || value === undefined) return null;
  if (Array.isArray(value)) return value.length ? [textPart(value.join("; "))] : null;
  const text = String(value);
  return text.trim() ? [textPart(text)] : null;
};

/** What a procedure item says as chosen: the standard with the person's fields, their own words, or a write-in. */
const procedureParts = (item, answer, standards, resolveBinding, bindingLabel = () => null) => {
  const mode = answer?.mode;
  if (mode === "custom") return nonBlank(answer.value) ? [textPart(String(answer.value).trim())] : null;
  if (mode !== "standard" || !item.standard) return null;
  const procedure = standards.procedures[item.standard];
  const fields = isObject(answer.fields) ? answer.fields : {};
  const sentences = procedure.script.map((sentence) =>
    fill(sentence, (name) => {
      const [kind, key] = name.split(/:(.*)/s);
      if (kind === "value") return valueParts(standards.values[key]?.value);
      if (kind === "field") return valueParts(nonBlank(fields[key]) ? String(fields[key]).trim() : null);
      if (kind === "bind") return valueParts(resolveBinding(key));
      return null;
    }, (name) => {
      const [kind, key] = name.split(/:(.*)/s);
      if (kind === "field") return (procedure.fields || []).find((f) => f.id === key)?.label ?? null;
      if (kind === "bind") return bindingLabel(key);
      return null;
    }));
  return tidy(sentences.flatMap((s, i) => (i === 0 ? s : [textPart(" "), ...s])));
};

/** The parts as plain text, a write-in as a line. */
export const partsText = (parts) => parts.map((p) => (p.writeIn !== undefined ? WRITE_IN : p.text)).join("");

/**
 * Compiles a brief.
 *
 * @param format    briefFormat(id): { template, bindings, constants, standards }
 * @param document  the person's brief (docs/MISSION_BRIEF.md §2.2): flow, constants, answers, scripts, options,
 *                  rallyPoints, include
 * @param plan      { name, lzs: [{id, name?, diagram}], routeSets: [{id, name, data}], aircraftProfiles,
 *                    missionAircraft, threats }
 * @param context   { generatedAt (ISO), utcOffsetMinutes, weather: {departure, arrival, enroute, stops}, notams }
 * @returns the BriefModel
 */
export const compileBrief = (format, document = {}, plan = {}, context = {}) => {
  const { template, bindings, constants: catalog, standards } = format;
  const doc = isObject(document) ? document : {};
  const offset = num(context.utcOffsetMinutes) ?? 0;
  const problems = [];

  const stops = (Array.isArray(plan.lzs) ? plan.lzs : []).filter((e) => isObject(e) && e.id !== undefined).map(readStop);
  const datedRoute = (Array.isArray(plan.routeSets) ? plan.routeSets : [])
    .flatMap((s) => (Array.isArray(s?.data?.routes) ? s.data.routes : []))
    .find((r) => /^\d{4}-\d{2}-\d{2}$/.test(r?.plan?.date || ""));
  const missionDate = datedRoute?.plan?.date || localDateOf(context.generatedAt, offset);
  const routes = (Array.isArray(plan.routeSets) ? plan.routeSets : [])
    .filter((s) => isObject(s) && s.id !== undefined)
    .flatMap((s) => readRoutes(s, missionDate));
  const flow = resolveFlow(doc.flow, stops, routes, problems);

  const brief = briefBindings({
    plan, document: doc, context: { ...context, standards }, flow, routes, stops, missionDate, offset,
  });
  const perRoute = new Map(flow.filter((f) => f.kind === "route").map((r) => [r.ref, routeBindings(r, problems)]));
  const perStop = new Map(flow.filter((f) => f.kind === "stop").map((s) => [s.ref, stopBindings(s, flow, context, plan)]));
  flow.filter((f) => f.kind === "stop" && !perStop.get(f.ref)["stop.arrive"]).forEach((s) =>
    problems.push({ ref: s.ref, message: `No route in the flow reaches ${s.name} before it, so it has no arrival time.` }));

  const bindingFor = (entry) => (name) => {
    if (!bindings[name]) return null;
    const scope = bindings[name].scope;
    if (scope === "brief") return brief[name] ?? null;
    if (!entry) return null;
    if (scope === "route") return entry.kind === "route" ? perRoute.get(entry.ref)?.[name] ?? null : null;
    return entry.kind === "stop" ? perStop.get(entry.ref)?.[name] ?? null : null;
  };

  // Constants: the person's list, else the ones offered; each typed, from the plan, standard, or a write-in.
  const constantEntries = Array.isArray(doc.constants)
    ? doc.constants.filter(isObject)
    : catalog.filter((c) => c.offered).map((c) => ({ id: c.id }));
  const constants = constantEntries.map((entry) => {
    const known = catalog.find((c) => c.id === entry.id);
    const label = known?.label ?? (String(entry.label ?? "").trim() || "Constant");
    const short = known?.short ?? label.toUpperCase().slice(0, 12);
    let value = null;
    let source = "blank";
    if (nonBlank(entry.value)) {
      value = String(entry.value).trim();
      source = "typed";
    } else if (known?.bind && brief[known.bind]) {
      value = brief[known.bind];
      source = "plan";
    } else if (known?.standard) {
      const v = standards.values[known.standard];
      value = `${v.value}${v.unit ? ` ${v.unit}` : ""}`;
      source = "standard";
    }
    return { id: String(entry.id ?? label), label, short, value, source };
  });
  const constantValue = (id) => constants.find((c) => c.id === id)?.value ?? null;

  // Steps, then their pages, so the packet's own page count can be said on its first page.
  const options = {
    script: true, constants: true, lzCards: true, navLogs: true, strips: true, iimc: true, writeIns: true,
    ...(isObject(doc.options) ? doc.options : {}),
  };
  const planned = planSteps(template, flow);
  const flowStops = flow.filter((f) => f.kind === "stop" && !f.gone);
  const flowRoutes = flow.filter((f) => f.kind === "route");
  const answers = isObject(doc.answers) ? doc.answers : {};
  const iimcItem = sectionsOf(template).flatMap((s) => s.items.map((it) => ({ s, it }))).find(({ it }) => it.standard === "iimc");
  const iimcAnswer = iimcItem ? answers[answerKey(iimcItem.s, iimcItem.it, null)] : null;
  const hasIimcCard = options.iimc && (iimcAnswer?.mode === "standard" || iimcAnswer?.mode === "custom");

  const pages = [];
  planned.forEach((st, i) => pages.push({ n: i + 1, kind: i === 0 ? "cover" : "step", stepId: stepIdOf(st) }));
  if (options.lzCards) flowStops.forEach((s) => pages.push({ n: pages.length + 1, kind: "lzCard", ref: s.ref, title: `${ROLE_NAMES[s.role]} card · ${s.name}` }));
  if (options.navLogs) flowRoutes.forEach((r) => pages.push({ n: pages.length + 1, kind: "navLog", ref: r.ref, title: `Nav log · ${r.name}` }));
  // Strip charts, three to a page, a route's strips together and in the order they are flown.
  const stripScale = STRIP_SCALES.includes(options.stripScale) ? options.stripScale : STRIP_SCALES[0];
  const threatsShown = doc.include?.threats === false ? [] : (Array.isArray(plan.threats) ? plan.threats : [])
    .filter((t) => isObject(t) && num(t.lat) !== null && num(t.lon) !== null);
  const strips = options.strips ? stripChartsOf(flowRoutes, flowStops.filter((s) => s.target), threatsShown, stripScale, problems) : [];
  flowRoutes.forEach((r) => {
    const own = strips.filter((st) => st.ref === r.ref);
    for (let i = 0; i < own.length; i += 3) {
      const shown = own.slice(i, i + 3);
      const parts = shown.length > 1 ? `${shown[0].part}–${shown[shown.length - 1].part}` : String(shown[0].part);
      const range = own.length > 3 ? ` (${parts} of ${own.length})` : "";
      pages.push({ n: pages.length + 1, kind: "strips", ref: r.ref, title: `Strip charts · ${r.name}${range}`, strips: shown.map((st) => st.id) });
    }
  });
  if (hasIimcCard) pages.push({ n: pages.length + 1, kind: "iimc", title: "IIMC card", itemId: `${iimcItem.s.id}.${iimcItem.it.id}` });

  // What the packet holds, by section, never by page: where Chromium breaks the printed pages is not known here, so a
  // page number the brief said could be wrong (the PDF's own footer numbers every page truly).
  const count = (kind) => pages.filter((p) => p.kind === kind).length;
  const inventory = [
    `the brief (${plural(planned.length, "step", "steps")})`,
    count("lzCard") ? `${plural(count("lzCard"), "LZ/PZ card", "LZ/PZ cards")}` : null,
    count("navLog") ? `${plural(count("navLog"), "nav log", "nav logs")}` : null,
    strips.length ? `${plural(strips.length, "strip chart", "strip charts")}` : null,
    count("iimc") ? "the IIMC card" : null,
  ].filter(Boolean);
  const inventoryText = inventory.length > 1 ? `${inventory.slice(0, -1).join(", ")} and ${inventory[inventory.length - 1]}` : inventory[0];
  brief["packet.pages"] = String(pages.length);
  brief["packet.inventory"] = inventoryText;

  const scripts = isObject(doc.scripts) ? doc.scripts : {};

  const steps = planned.map((st, index) => {
    const { group, entry } = st;
    const id = stepIdOf(st);
    const first = group.sections[0];
    const resolveBinding = bindingFor(entry);
    const instanceName = entry ? entry.name : null;
    const baseTitle = first.stepTitle ?? (group.sections.length > 1 ? first.paragraph.title : first.title);
    const lastSection = group.sections[group.sections.length - 1];

    const items = [];
    const sentences = [];
    group.sections.forEach((section) => {
      const itemValues = new Map();
      section.items.forEach((item) => {
        const key = answerKey(section, item, entry);
        const answer = answers[key];
        let parts = null;
        let source = "blank";
        if (item.kind === "info") {
          source = "info";
        } else if (item.kind === "procedure") {
          parts = procedureParts(item, answer, standards, resolveBinding, (key) => bindings[key]?.label ?? null);
          source = parts ? answer.mode : "blank";
        } else if (nonBlank(answer?.value) || (Array.isArray(answer?.value) && answer.value.length)) {
          parts = valueParts(answer.value);
          source = "typed";
        } else if (item.bind) {
          parts = valueParts(resolveBinding(item.bind));
          source = parts ? "plan" : "blank";
        }
        itemValues.set(item.id, parts);
        items.push({
          id: `${section.id}.${item.id}`,
          key,
          label: item.label,
          kind: item.kind,
          source,
          text: parts ? partsText(parts) : null,
          writeIn: parts === null && item.kind !== "info",
        });
      });
      section.script.forEach((sentence) =>
        sentences.push(fill(sentence, (name) => {
          if (itemValues.has(name)) return itemValues.get(name);
          const [kind, key] = name.split(/:(.*)/s);
          if (kind === "bind") return valueParts(resolveBinding(key));
          if (kind === "const") return valueParts(constantValue(key));
          if (kind === "item") {
            const dot = key.lastIndexOf(".");
            const other = sectionsOf(template).find((s) => s.id === key.slice(0, dot));
            const otherItem = other?.items.find((it) => it.id === key.slice(dot + 1));
            if (!otherItem) return null;
            const answer = answers[answerKey(other, otherItem, null)];
            if (otherItem.kind === "procedure") return procedureParts(otherItem, answer, standards, resolveBinding, (k) => bindings[k]?.label ?? null);
            if (nonBlank(answer?.value)) return valueParts(answer.value);
            return otherItem.bind ? valueParts(resolveBinding(otherItem.bind)) : null;
          }
          return null;
        }, (name) => {
          const own = section.items.find((it) => it.id === name);
          if (own) return own.label;
          const [kind, key] = name.split(/:(.*)/s);
          if (kind === "bind") return bindings[key]?.label ?? null;
          if (kind === "const") return constants.find((c) => c.id === key)?.label ?? null;
          if (kind === "item") {
            const dot = key.lastIndexOf(".");
            return sectionsOf(template).find((s) => s.id === key.slice(0, dot))?.items.find((it) => it.id === key.slice(dot + 1))?.label ?? null;
          }
          return null;
        })));
    });

    const edited = typeof scripts[id] === "string" && scripts[id].trim() !== "";
    const facts = [...new Set(group.sections.flatMap((s) => s.facts || []))].map((name) => ({
      key: name,
      label: bindings[name].label,
      value: (() => {
        const v = resolveBinding(name);
        return Array.isArray(v) ? v.join("; ") : v ?? null;
      })(),
    }));

    return {
      id,
      number: group.sections.length > 1 ? `${first.id}–${lastSection.id}` : first.id,
      title: instanceName ? `${baseTitle} · ${instanceName}` : baseTitle,
      who: first.who ?? first.paragraph.who ?? template.defaultWho,
      sections: group.sections.map((s) => s.id),
      ref: entry ? entry.ref : null,
      role: entry?.kind === "stop" ? entry.role : null,
      page: index + 1,
      figure: figureOf(first.figure, entry, flow, doc),
      facts,
      items,
      script: edited ? [[textPart(scripts[id].trim())]] : sentences.filter((s) => s.length > 0),
      scriptEdited: edited,
    };
  });

  return {
    format: BRIEF_MODEL_FORMAT,
    version: BRIEF_MODEL_VERSION,
    template: { id: template.id, version: template.version, title: template.title, shortTitle: template.shortTitle },
    generatedAt: context.generatedAt ?? null,
    utcOffsetMinutes: offset,
    mission: { name: brief["mission.name"], date: brief["mission.date"], summary: brief["flight.summary"] },
    options,
    constants,
    places: placesOf(flow, offset),
    geometry: geometryOf(flow, plan, doc),
    flow: flow.map((f) => (f.gone
      ? { ref: f.ref, gone: true }
      : f.kind === "route"
        ? { ref: f.ref, kind: "route", name: f.name, start: perRoute.get(f.ref)["route.start"], end: perRoute.get(f.ref)["route.end"] }
        : { ref: f.ref, kind: "stop", role: f.role, name: f.name, arrive: perStop.get(f.ref)["stop.arrive"], grid: perStop.get(f.ref)["stop.grid"] })),
    steps,
    pages,
    strips,
    problems,
  };
};

/**
 * What the figures draw, once for the whole brief (each figure names what it shows by reference), in lat/lon with the
 * words already written, so the server that draws them decides nothing (docs/MISSION_BRIEF.md §2.6):
 *  - routes: each route in the flow, by ref: its name and colour, its line through every point (shaping points too, so
 *    it is the path flown), its named points, and its nav log's rows (the leg to each named point);
 *  - stops: each LZ/PZ in the flow, by ref: its name, role, target, boundary, aircraft, landing and takeoff headings
 *    and go-around side;
 *  - threats (unless the brief leaves them out) and the rally points.
 */
const geometryOf = (flow, plan, doc) => {
  const routes = {};
  const stops = {};
  const pair = (lat, lon) => (num(lat) !== null && num(lon) !== null ? [lat, lon] : null);
  flow.filter((f) => f.kind === "route").forEach((r) => {
    const points = r.result?.points || [];
    routes[r.ref] = {
      name: r.name,
      color: typeof r.route.color === "string" ? r.route.color : null,
      line: (r.route.points || []).map((p) => pair(p.lat, p.lon)).filter(Boolean),
      points: points.map((p) => ({ name: pointName(p.name, "PT"), type: p.ptType ?? null, lat: p.lat, lon: p.lon })),
      log: points.slice(1).map((p, i) => ({
        from: pointName(points[i].name, "PT"),
        to: pointName(p.name, "PT"),
        course: headingText(p.legCourseTrueDeg),
        distance: nmText(p.legDistNm),
        time: p.legTimeSec !== null ? durationText(p.legTimeSec) : null,
        clock: clockText(wallOf(p.clockTime)),
        altitude: altitudeText(p),
        airspeed: airspeedText(p.airspeed),
      })),
    };
  });
  flow.filter((f) => f.kind === "stop" && f.target).forEach((stop) => {
    const { diagram } = stop;
    const boundary = diagram.analysis?.customLZ || diagram.analysis?.detectedLZ;
    const landing = landingHeadingOf(diagram.flightData?.landing_hdg);
    const takeoff = landingHeadingOf(diagram.flightData?.takeoff_hdg);
    stops[stop.ref] = {
      name: stop.name,
      role: stop.role,
      lat: stop.target.lat,
      lon: stop.target.lon,
      boundary: Array.isArray(boundary) ? boundary.map((p) => (Array.isArray(p) ? pair(p[0], p[1]) : null)).filter(Boolean) : null,
      aircraft: (diagram.graphics?.helicopters || [])
        .map((h) => (pair(h?.lat, h?.lon) ? { lat: h.lat, lon: h.lon, heading: num(Number(h.heading)) ?? 0 } : null))
        .filter(Boolean),
      landingHeading: landing,
      takeoffHeading: takeoff,
      goAround: goAroundSide(diagram),
    };
  });
  const threats = doc.include?.threats === false ? [] : (Array.isArray(plan.threats) ? plan.threats : [])
    .filter((t) => isObject(t) && num(t.lat) !== null && num(t.lon) !== null)
    .map((t) => ({ name: String(t.name ?? t.label ?? "").trim() || "Threat", lat: t.lat, lon: t.lon }));
  const rally = (Array.isArray(doc.rallyPoints) ? doc.rallyPoints : []).map((r) => {
    const route = flow.find((f) => f.kind === "route" && f.ref === r?.ref);
    const point = route?.named.find((p) => p.id === r?.pointId);
    return point ? { name: pointName(point.name, "RALLY"), label: r.label === "outbound" ? "outbound" : "inbound", lat: point.lat, lon: point.lon } : null;
  }).filter(Boolean);
  return { routes, stops, threats, rally };
};

/**
 * Where and when the weather is wanted: the departure (the first route's first point, at its clock), the arrival (the last
 * route's last point) and each stop (its target, at the time a route reaches it), each time as an instant at the offset;
 * null where the plan does not say. The caller asks /api/weather with these and hands the answers back as `context.weather`.
 */
const placesOf = (flow, offset) => {
  const instant = (wall) => (wall === null ? null : new Date(wall - offset * 60000).toISOString());
  const routes = flow.filter((f) => f.kind === "route" && f.result?.points?.length);
  const first = routes[0]?.result.points[0] ?? null;
  const lastRoute = routes[routes.length - 1];
  const last = lastRoute ? lastRoute.result.points[lastRoute.result.points.length - 1] : null;
  const place = (p) => (p ? { lat: p.lat, lon: p.lon, at: instant(wallOf(p.clockTime)) } : null);
  return {
    departure: place(first),
    arrival: place(last),
    stops: flow.filter((f) => f.kind === "stop" && f.target).map((stop) => {
      const before = flow.slice(0, flow.indexOf(stop)).reverse().find((f) => f.kind === "route");
      const at = before ? routePointAt(before, stop.target) : null;
      return { id: stop.id, ref: stop.ref, lat: stop.target.lat, lon: stop.target.lon, at: at ? instant(wallOf(at.point.clockTime)) : null };
    }),
  };
};

/**
 * What a step's figure shows, as data the renderer draws: the area, the item in focus, and what is on it. The item in
 * focus is the step's own (a route, an LZ/PZ), and at the departure the route the flight leaves on, which is lit.
 */
const figureOf = (kind, entry, all, doc) => {
  if (kind === "none") return null;
  const routesIn = all.filter((f) => f.kind === "route");
  const stopsIn = all.filter((f) => f.kind === "stop" && f.target);
  const pointsOf = (r) => (r.result?.points || []).map((p) => [p.lat, p.lon]);
  let focus = [];
  if (kind === "route" && entry) focus = pointsOf(entry);
  else if (kind === "stop" && entry?.target) focus = stopArea(entry);
  else if (kind === "departure" && routesIn[0]) focus = pointsOf(routesIn[0]).slice(0, 3);
  else focus = [...routesIn.flatMap(pointsOf), ...stopsIn.map((s) => [s.target.lat, s.target.lon])];
  return {
    kind,
    ref: entry ? entry.ref : kind === "departure" && routesIn[0] ? routesIn[0].ref : null,
    bounds: boundsOf(focus),
    routes: routesIn.map((r) => r.ref),
    stops: stopsIn.map((s) => s.ref),
    rallyPoints: kind === "rally" && Array.isArray(doc.rallyPoints) ? doc.rallyPoints.filter(isObject).map((r) => ({ ref: r.ref, pointId: r.pointId })) : [],
  };
};

/** An LZ/PZ's area for its figure: its boundary if it has one, else its target, padded to about 300 m either way. */
const stopArea = (stop) => {
  const boundary = stop.diagram.analysis?.customLZ || stop.diagram.analysis?.detectedLZ;
  const corners = Array.isArray(boundary) ? boundary.filter((p) => Array.isArray(p) && num(p[0]) !== null && num(p[1]) !== null) : [];
  const { lat, lon } = stop.target;
  const dLat = 300 / 111320;
  const dLon = dLat / Math.max(0.01, Math.cos((lat * Math.PI) / 180));
  return [...corners, [lat - dLat, lon - dLon], [lat + dLat, lon + dLon]];
};

/** [[south, west], [north, east]] round the points, a tenth wider each way (at least 0.002°); null for none. */
const boundsOf = (points) => {
  const valid = points.filter((p) => num(p[0]) !== null && num(p[1]) !== null);
  if (valid.length === 0) return null;
  let s = Infinity;
  let w = Infinity;
  let n = -Infinity;
  let e = -Infinity;
  valid.forEach(([lat, lon]) => {
    s = Math.min(s, lat); n = Math.max(n, lat); w = Math.min(w, lon); e = Math.max(e, lon);
  });
  const padLat = Math.max(0.002, (n - s) * 0.1);
  const padLon = Math.max(0.002, (e - w) * 0.1);
  return [[s - padLat, w - padLon], [n + padLat, e + padLon]];
};

// ---------------------------------------------------------------------------------------------------------------
// Strip charts (docs/MISSION_BRIEF.md §2.6): each route along its course, cut into strips a kneeboard holds.

/** A strip as printed: 5.5 by 21.5 cm, three to a page, the direction of flight up. */
export const STRIP_CM = { width: 5.5, length: 21.5 };
/** The scales a strip is drawn at; the first is used unless the brief says another. */
export const STRIP_SCALES = [100000, 250000];
/** More strips than this are not made: a brief is a packet, not an atlas. */
export const MAX_STRIPS = 60;

const R_METRES = 3440.065 * METRES_PER_NM; // the planner's sphere (routeCalc's distanceNm), in metres
const RAD = Math.PI / 180;
const round7 = (v) => Math.round(v * 1e7) / 1e7;

/** Ground sizes of a strip at a scale: its length and width, the margin kept at each end, and how far off its axis the route may run. */
const stripDims = (scale) => {
  const length = (STRIP_CM.length / 100) * scale;
  const width = (STRIP_CM.width / 100) * scale;
  const margin = length * 0.05;
  return { length, width, margin, usable: length - 2 * margin, maxCross: width * 0.3 };
};

/** A place as metres east and north of an origin, on a plane fitted at their middle latitude. */
const localOf = (origin, p) => {
  const mid = ((origin.lat + p.lat) / 2) * RAD;
  return { x: (p.lon - origin.lon) * RAD * R_METRES * Math.cos(mid), y: (p.lat - origin.lat) * RAD * R_METRES };
};

/** The place `x` metres east and `y` north of an origin (the inverse of localOf). */
const placeOf = (origin, x, y) => {
  const lat = origin.lat + y / R_METRES / RAD;
  const mid = ((origin.lat + lat) / 2) * RAD;
  return { lat, lon: origin.lon + x / (R_METRES * Math.max(1e-9, Math.cos(mid))) / RAD };
};

/**
 * The direction from a piece's first point to its last, as a unit vector; its first real movement while it has no
 * length; and, for a piece that has not moved a metre yet, `none` (pointing north, which nothing then relies on).
 */
const axisOf = (local) => {
  const end = local[local.length - 1];
  const chord = Math.hypot(end.x, end.y);
  const ahead = chord >= 1 ? end : local.find((q) => Math.hypot(q.x, q.y) >= 1);
  if (!ahead) return { ux: 0, uy: 1, chord, none: true };
  const len = Math.hypot(ahead.x, ahead.y);
  return { ux: ahead.x / len, uy: ahead.y / len, chord, none: false };
};

/**
 * Whether a run of the route fits one strip: no longer than the strip less its margins, never more than `maxCross`
 * off the line from its start to its end (labels need the room either side), and never going back along it (the
 * direction of flight stays up the strip).
 */
const fitsStrip = (piece, dims) => {
  const origin = piece[0];
  const local = piece.map((p) => localOf(origin, p));
  const { ux, uy, chord } = axisOf(local);
  if (chord > dims.usable) return false;
  let before = 0;
  return local.every((q) => {
    const along = q.x * ux + q.y * uy;
    const cross = q.x * uy - q.y * ux;
    const fits = Math.abs(cross) <= dims.maxCross && along >= before - 1 && along <= dims.usable;
    before = along;
    return fits;
  });
};

const between = (a, b, t) => ({ lat: a.lat + (b.lat - a.lat) * t, lon: a.lon + (b.lon - a.lon) * t, pos: a.pos + (b.pos - a.pos) * t });

/** A strip carries on into the next leg only when that leg keeps within this many degrees of the strip's course. */
const MAX_CARRY_TURN = 20;

/** How far, in degrees, the leg from a piece's end to `next` turns off the piece's own course; none for a piece with no course yet. */
const turnAt = (piece, next) => {
  const origin = piece[0];
  const { ux, uy, none } = axisOf(piece.map((p) => localOf(origin, p)));
  if (none) return 0;
  const from = piece[piece.length - 1];
  const leg = localOf(from, next);
  const len = Math.hypot(leg.x, leg.y);
  if (len < 1) return 0;
  return Math.acos(Math.max(-1, Math.min(1, (leg.x * ux + leg.y * uy) / len))) / RAD;
};

/**
 * The route's path cut into strips: as far as each strip holds it, then the next from where that one ended, so a bend
 * the strip cannot hold starts a new one and a long leg runs on over several. Each piece is points along the path with
 * `pos`, its place along the route's own points (a whole number at a point, between at a cut).
 */
const cutIntoStrips = (path, dims, budget) => {
  const pieces = [];
  let piece = [path[0]];
  for (let i = 1; i < path.length; ) {
    // Past what a brief may hold (and what the person is told about), no more is cut: a far-flung point costs nothing.
    if (pieces.length >= budget) return { pieces, more: true };
    const next = path[i];
    if (fitsStrip([...piece, next], dims)) {
      piece.push(next);
      i += 1;
      continue;
    }
    const from = piece[piece.length - 1];
    if (piece.length === 1) {
      // A leg longer than a strip, on its own: cut into as few equal parts as the strip holds, so none is a stub.
      const parts = Math.ceil(metresBetween(from, next) / (dims.usable * 0.995));
      if (parts <= 1) {
        piece.push(next);
        i += 1;
      } else {
        piece.push(between(from, next, 1 / parts));
      }
    } else if (turnAt(piece, next) <= MAX_CARRY_TURN) {
      // On along a leg that keeps the course, as far as still fits, unless that is next to nothing. A real turn ends the
      // strip at the turn point instead, so the next strip starts there.
      let lo = 0;
      let hi = 1;
      for (let k = 0; k < 40; k += 1) {
        const mid = (lo + hi) / 2;
        if (fitsStrip([...piece, between(from, next, mid)], dims)) lo = mid;
        else hi = mid;
      }
      const leg = metresBetween(from, next);
      let reach = leg * lo;
      // Leave the rest of the leg at least a quarter of a strip, so the next strip is never a stub before a turn.
      if (reach < leg && leg - reach < dims.usable * 0.25) reach = leg - dims.usable * 0.25;
      if (reach >= dims.usable * 0.05) piece.push(between(from, next, reach / leg));
    }
    pieces.push(piece);
    piece = [piece[piece.length - 1]];
  }
  if (piece.length >= 2) {
    // What is left past the last cut, if it is shorter than a strip's margin, is drawn in the margin of the strip before.
    const last = pieces[pieces.length - 1];
    const along = piece.slice(1).reduce((sum, p, k) => sum + metresBetween(piece[k], p), 0);
    if (last && along < dims.margin) last.push(...piece.slice(1));
    else pieces.push(piece);
  }
  return { pieces, more: false };
};

/** Distance along the route's own points to a place on it (`pos`), and back. */
const distanceAlong = (points) => {
  const sums = [0];
  for (let i = 1; i < points.length; i += 1) sums.push(sums[i - 1] + metresBetween(points[i - 1], points[i]));
  const at = (pos) => {
    const i = Math.min(points.length - 1, Math.max(0, Math.floor(pos)));
    if (i >= points.length - 1) return sums[points.length - 1];
    return sums[i] + (sums[i + 1] - sums[i]) * (pos - i);
  };
  const posAt = (metres) => {
    for (let i = 1; i < points.length; i += 1) {
      if (metres <= sums[i] || i === points.length - 1) {
        const span = sums[i] - sums[i - 1];
        return i - 1 + (span > 0 ? Math.min(1, Math.max(0, (metres - sums[i - 1]) / span)) : 0);
      }
    }
    return 0;
  };
  return { at, posAt };
};

/** A longitude brought to within 180° of another, so places either side of the antimeridian are measured the short way. */
const besideLon = (lon, near) => lon + 360 * Math.round((near - lon) / 360);

/** Whether a place lies on a strip: its rectangle from `margin` behind the start, along the axis, `width` across. */
const onStrip = (origin, ux, uy, dims, p) => {
  const q = localOf(origin, { lat: p.lat, lon: besideLon(p.lon, origin.lon) });
  const along = q.x * ux + q.y * uy;
  const cross = q.x * uy - q.y * ux;
  return along >= -dims.margin && along <= dims.length - dims.margin && Math.abs(cross) <= dims.width / 2;
};

/**
 * The strip charts of the routes in the flow, as data the server draws (it only projects, turns and writes): for each
 * strip its route and part, scale, the course up it (true, as a number to turn by and as text), its centre and ground
 * size, the route's path along it, the checkpoints on it, a note on each leg it holds (course, distance, time and
 * altitude, the nav log's words, put at the middle of the leg's run on the strip), and the LZ/PZs and threats that lie
 * on it. A route with fewer than two named points has none. The MGRS grid is added when the PDF is asked for
 * (stripGrid.js), so this stays exact. Numbers are rounded (places to 1e-7°, the course to 0.01°) so a port gives the same.
 */
const stripChartsOf = (flowRoutes, flowStops, threats, scale, problems) => {
  const dims = stripDims(scale);
  const strips = [];
  flowRoutes.forEach((r) => {
    const points = (r.route.points || []).map((p, pos) => ({ lat: p.lat, lon: p.lon, pos, id: p.id, kind: p.kind, name: p.name, ptType: p.ptType }));
    if (points.some((p) => num(p.lat) === null || num(p.lon) === null)) return;
    const namedAt = points.filter((p) => p.kind !== "shaping" && p.id);
    const planned = r.result?.points || [];
    if (namedAt.length < 2 || planned.length < 2) return;
    if (points.some((p) => Math.abs(p.lat) > 84 || Math.abs(p.lon) > 720)) {
      problems.push({ ref: r.ref, message: `${r.name} runs beyond 84° of latitude, past what the map and the MGRS grid show: it has no strip charts.` });
      return;
    }
    // Each place within 180° of longitude of the one before, so a route across the antimeridian is cut the short way.
    points.forEach((p, k) => {
      if (k > 0) p.lon = besideLon(p.lon, points[k - 1].lon);
    });
    const path = points.slice(namedAt[0].pos, namedAt[namedAt.length - 1].pos + 1);
    const flown = path.slice(1).reduce((sum, p, k) => sum + metresBetween(path[k], p), 0);
    if (flown < 1) return;
    const allowed = MAX_STRIPS - strips.length;
    const { pieces, more } = cutIntoStrips(path, dims, allowed);
    const along = distanceAlong(points);
    const made = [];
    pieces.forEach((piece) => {
      if (made.length >= allowed) return;
      const origin = piece[0];
      const local = piece.map((p) => localOf(origin, p));
      const { ux, uy } = axisOf(local);
      const course = Math.round((((Math.atan2(ux, uy) / RAD) % 360) + 360) % 360 * 100) / 100 % 360;
      const centreAlong = dims.length / 2 - dims.margin;
      const centre = placeOf(origin, ux * centreAlong, uy * centreAlong);
      const first = piece[0].pos;
      const last = piece[piece.length - 1].pos;
      const legs = [];
      for (let k = 0; k + 1 < namedAt.length; k += 1) {
        const from = Math.max(first, namedAt[k].pos);
        const to = Math.min(last, namedAt[k + 1].pos);
        if (!(to > from)) continue;
        const middle = along.posAt((along.at(from) + along.at(to)) / 2);
        const i = Math.min(points.length - 2, Math.floor(middle));
        const at = between(points[i], points[i + 1], middle - i);
        const leg = planned[k + 1];
        legs.push({
          from: pointName(namedAt[k].name, "PT"),
          to: pointName(namedAt[k + 1].name, "PT"),
          at: [round7(at.lat), round7(at.lon)],
          course: headingText(leg.legCourseTrueDeg),
          distance: nmText(leg.legDistNm),
          time: leg.legTimeSec !== null ? durationText(leg.legTimeSec) : null,
          altitude: altitudeText(leg),
        });
      }
      made.push({
        ref: r.ref,
        name: r.name,
        scale,
        course,
        courseText: headingText(course),
        centre: [round7(centre.lat), round7(centre.lon)],
        length: dims.length,
        width: dims.width,
        path: piece.map((p) => [round7(p.lat), round7(p.lon)]),
        points: namedAt
          .filter((p) => p.pos >= first - 1e-9 && p.pos <= last + 1e-9)
          .map((p) => ({ name: pointName(p.name, "PT"), type: p.ptType ?? null, lat: p.lat, lon: p.lon })),
        legs,
        stops: flowStops
          .filter((s) => onStrip(origin, ux, uy, dims, s.target))
          .map((s) => ({ name: s.name, role: s.role, lat: s.target.lat, lon: besideLon(s.target.lon, origin.lon) })),
        threats: threats
          .filter((t) => onStrip(origin, ux, uy, dims, t))
          .map((t) => ({ name: String(t.name ?? t.label ?? "").trim() || "Threat", lat: t.lat, lon: besideLon(t.lon, origin.lon) })),
      });
    });
    if (more || made.length < pieces.length) {
      problems.push({ ref: r.ref, message: `Strip charts stop at ${MAX_STRIPS} strips: the rest of ${r.name} has none.` });
    }
    made.forEach((strip, i) => strips.push({ id: `${r.ref}#${i + 1}`, ...strip, part: i + 1, parts: made.length }));
  });
  return strips;
};

/** A step's script as plain sentences, a write-in as a line: for the sandtable and anything that reads text. */
export const scriptText = (step) => step.script.map(partsText);
