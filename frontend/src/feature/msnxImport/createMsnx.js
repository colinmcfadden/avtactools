import JSZip from "jszip";
import { MISSION_PATHS } from "./parseMsnx.js";
import {
  MSNX_NS,
  findDirectChild,
  formatCoordinate,
  findCoordinateValueEl,
  setItemValue,
  serializeXmlPart,
  downloadBlob,
} from "./mutateMsnx.js";
import { computeRoutePlan, defaultRoutePlan } from "./routeCalc.js";
import {
  FT_TO_M,
  formatAmpsAirspeed,
  formatAmpsWind,
  formatAmpsPlanAltitude,
  formatAmpsCmdAlt,
  formatAmpsElevation,
  formatAmpsClockTime,
} from "./ampsFormats.js";

const TEMPLATE_URL = "/msnx_template.msnx";

const TEMPLATE_PATHS = {
  ...MISSION_PATHS,
  routes: "mission/routes.xml",
  mission: "mission/mission.xml",
  missionsummary: "mission/missionsummary.xml",
};

// PtType vocabulary from Mission X (see the sample mission's points.xml).
const PT_TYPE_VALUES = {
  turn: "RtePtTurn,Circle.png:",
  ip: "RtePtIP,Square.png:",
  target: "RtePtTarget,Triangle.png:",
};
const CALC_PT_TYPE = "CalcPtSerpentine,SmallCircle.png:";

const directChildren = (el, tagName) =>
  Array.from(el.children).filter((c) => c.tagName === tagName);

const clearChildren = (el) => {
  while (el.firstChild) el.removeChild(el.firstChild);
};

const setDirectChildText = (el, tagName, text) => {
  const child = findDirectChild(el, tagName);
  if (child) child.textContent = text;
};

const getItemValue = (el, key) => {
  for (const item of el.getElementsByTagName("item")) {
    const keyEl = findDirectChild(item, "key");
    if (keyEl && keyEl.textContent === key) {
      const attributeEl = findDirectChild(item, "attribute");
      const valueEl = attributeEl && findDirectChild(attributeEl, "value");
      return valueEl?.textContent ?? null;
    }
  }
  return null;
};

const parseXml = (text, label) => {
  const doc = new DOMParser().parseFromString(text, "application/xml");
  if (doc.querySelector("parsererror")) {
    throw new Error(`Failed to parse ${label} in the sketch template.`);
  }
  return doc;
};

/** Replaces the ordered <id> children of a list element (e.g. <legs>, <routes>). */
const setIdList = (doc, listEl, ids) => {
  clearChildren(listEl);
  for (const id of ids) {
    const idEl = doc.createElement("id");
    idEl.textContent = id;
    listEl.appendChild(idEl);
  }
};

/**
 * Gives a cloned point/leg's commandbase fresh GUIDs (commandbase id,
 * commandsequence ids, command item keys/ids stay mutually consistent).
 * Returns oldCommandId -> newCommandId so callers can mirror the new ids
 * into the gpx (rtept commands / manifest leg entries reference them).
 */
const regenerateCommandIds = (containerEl) => {
  const map = new Map();
  const commandbase = findDirectChild(containerEl, "commandbase");
  if (!commandbase) return map;

  const cbIdEl = findDirectChild(commandbase, "id");
  if (cbIdEl) cbIdEl.textContent = crypto.randomUUID();

  const seq = findDirectChild(commandbase, "commandsequence");
  for (const idEl of seq ? directChildren(seq, "id") : []) {
    const newId = crypto.randomUUID();
    map.set(idEl.textContent, newId);
    idEl.textContent = newId;
  }

  const commands = findDirectChild(commandbase, "commands");
  for (const item of commands ? directChildren(commands, "item") : []) {
    const keyEl = findDirectChild(item, "key");
    const newId = map.get(keyEl?.textContent);
    if (!newId) continue;
    keyEl.textContent = newId;
    const cmdEl = findDirectChild(item, "command");
    const cmdIdEl = cmdEl && findDirectChild(cmdEl, "id");
    if (cmdIdEl) cmdIdEl.textContent = newId;
  }

  return map;
};

/** Finds a leg commandbase's command item + id by its CmdTypeID value. */
const findCommandByType = (legEl, cmdType) => {
  const commandbase = findDirectChild(legEl, "commandbase");
  const commands = commandbase && findDirectChild(commandbase, "commands");
  for (const item of commands ? directChildren(commands, "item") : []) {
    const cmdEl = findDirectChild(item, "command");
    if (cmdEl && getItemValue(cmdEl, "CmdTypeID") === cmdType) {
      return { item, commandsEl: commands, id: findDirectChild(cmdEl, "id")?.textContent };
    }
  }
  return { item: null, commandsEl: commands, id: null };
};

const removeCommand = (legEl, cmdType) => {
  const { item, commandsEl } = findCommandByType(legEl, cmdType);
  if (!item) return;
  const cmdEl = findDirectChild(item, "command");
  const cmdId = findDirectChild(cmdEl, "id")?.textContent;
  commandsEl.removeChild(item);
  const commandbase = findDirectChild(legEl, "commandbase");
  const seq = findDirectChild(commandbase, "commandsequence");
  for (const idEl of seq ? directChildren(seq, "id") : []) {
    if (idEl.textContent === cmdId) seq.removeChild(idEl);
  }
};

/**
 * Splits a sketch's point list into AMPS leg vertices and the shaping points
 * that fall on each leg between consecutive vertices. Points without a `kind`
 * (from sketches made before designations existed) count as AMPS points.
 */
const partitionSketch = (sketch) => {
  const ampsPoints = [];
  const legShaping = [];
  let current = null;
  for (const p of sketch.points) {
    if (p.kind !== "shaping") {
      ampsPoints.push(p);
      if (ampsPoints.length > 1) legShaping.push(current || []);
      current = [];
    } else if (current) {
      current.push(p);
    }
  }
  if (ampsPoints.length < 2) {
    throw new Error(
      `Route "${sketch.name}" needs at least two designated AMPS points to export.`,
    );
  }
  return { ampsPoints, legShaping };
};

/**
 * Builds a brand-new .msnx zip from the template bytes and the sketched
 * routes. Designated points become full AMPS route points; shaping points
 * become serpentine geometry (leg trackpointset + CalcPtSerpentine display
 * points), mirroring how Mission X encodes hand-shaped legs. Performance
 * data stays stale from the prototypes — recalculated in AMPS.
 */
export async function buildSketchMsnxZip(templateData, sketchedRoutes, missionName) {
  const zip = await JSZip.loadAsync(templateData);

  const docs = {};
  for (const [key, path] of Object.entries(TEMPLATE_PATHS)) {
    const entry = zip.file(path);
    if (!entry) throw new Error(`Mission template is missing ${path}.`);
    docs[key] = parseXml(await entry.async("string"), path);
  }

  // --- prototypes (cloned before their containers are cleared) ---
  const routesRoot = docs.routes.getElementsByTagName("routes")[0];
  const routeProto = directChildren(routesRoot, "route")[0].cloneNode(true);

  const segmentsRoot = docs.segments.getElementsByTagName("segments")[0];
  const segmentProto = directChildren(segmentsRoot, "segment")[0].cloneNode(true);

  const legsRoot = docs.legs.getElementsByTagName("legs")[0];
  const legProto = directChildren(legsRoot, "leg")[0].cloneNode(true);

  const protoTrackpointset = findDirectChild(findDirectChild(legProto, "commandbase"), "trackpointset");
  const trackpointItemProto = directChildren(protoTrackpointset, "item")[0]?.cloneNode(true);
  if (!trackpointItemProto) {
    throw new Error("Mission template's leg has no trackpoint to clone.");
  }

  const pointsRoot = docs.points.getElementsByTagName("points")[0];
  const templatePoints = directChildren(pointsRoot, "point");
  const ampsPointProto = templatePoints
    .find((p) => getItemValue(p, "IsCalcPt") === "False")
    ?.cloneNode(true);
  const calcPointProto = templatePoints
    .find((p) => (getItemValue(p, "PtType") || "").startsWith("CalcPtSerpentine"))
    ?.cloneNode(true);
  if (!ampsPointProto || !calcPointProto) {
    throw new Error("Mission template is missing the point prototypes.");
  }
  const ampsPointProtoId = findDirectChild(ampsPointProto, "id").textContent;
  const calcPointProtoId = findDirectChild(calcPointProto, "id").textContent;

  const gpxRoot = docs.gpx.getElementsByTagName("gpx")[0];
  const rteProto = gpxRoot.getElementsByTagName("rte")[0].cloneNode(true);
  const findProtoRtept = (pid) =>
    Array.from(rteProto.getElementsByTagName("rtept"))
      .find((r) => r.getElementsByTagName("msnx:point")[0]?.getAttribute("msnx:id") === pid)
      ?.cloneNode(true);
  const rteptAmpsProto = findProtoRtept(ampsPointProtoId);
  const rteptCalcProto = findProtoRtept(calcPointProtoId);
  if (!rteptAmpsProto || !rteptCalcProto) {
    throw new Error("Mission template's GPX is missing the prototype route points.");
  }

  const rootExtensions = directChildren(gpxRoot, "extensions")[0];
  const msnxMission = rootExtensions.getElementsByTagName("msnx:mission")[0];
  const manifestSegmentsEl = directChildren(msnxMission, "msnx:segments")[0];
  const manifestSegmentProto = manifestSegmentsEl.children[0].cloneNode(true);
  const manifestLegsEl = directChildren(msnxMission, "msnx:legs")[0];
  const manifestLegProto = manifestLegsEl.children[0].cloneNode(true);
  const manifestPointPlaceholderProto = Array.from(
    manifestLegProto.getElementsByTagName("msnx:point"),
  )[0]?.cloneNode(true);

  const summaryRoutesRoot = docs.missionsummary.getElementsByTagName("routes")[0];
  const summaryRouteProto = directChildren(summaryRoutesRoot, "route")[0].cloneNode(true);

  // --- clear all mission content; rebuild from the sketches ---
  clearChildren(routesRoot);
  clearChildren(segmentsRoot);
  clearChildren(legsRoot);
  clearChildren(pointsRoot);
  clearChildren(summaryRoutesRoot);
  clearChildren(manifestSegmentsEl);
  clearChildren(manifestLegsEl);
  for (const rte of Array.from(gpxRoot.getElementsByTagName("rte"))) {
    rte.parentNode.removeChild(rte);
  }

  let firstIds = null;

  for (const sketch of sketchedRoutes) {
    const { ampsPoints, legShaping } = partitionSketch(sketch);

    // Planned performance data (airspeeds, altitudes, winds, TOT-anchored
    // clock times) computed the same way the panel displays it. planResult
    // points/legs are index-aligned with ampsPoints (same shaping filter).
    const plan = { ...defaultRoutePlan(), ...(sketch.plan || {}) };
    const planResult = computeRoutePlan(sketch, plan, sketch.elevations || {});

    const routeId = crypto.randomUUID();
    const segmentId = crypto.randomUUID();
    const ampsIds = ampsPoints.map(() => crypto.randomUUID());
    const legIds = legShaping.map(() => crypto.randomUUID());
    // Per-amps-point gpx command id (regenerated from the point clone) and
    // per-leg command ids, mirrored into the gpx manifest below.
    const ampsCmdIds = [];
    const legCmdInfo = [];
    // Calc point ids per leg, for the gpx rtepts.
    const calcIdsPerLeg = legShaping.map((shaping) => shaping.map(() => crypto.randomUUID()));

    if (!firstIds) {
      firstIds = { routeId, segmentId, pointId: ampsIds[0] };
    }

    const ampsName = (p, i) => p.name || `.ACP${i}`;

    // --- points.xml: AMPS points ---
    ampsPoints.forEach((point, i) => {
      const clone = ampsPointProto.cloneNode(true);
      findDirectChild(clone, "id").textContent = ampsIds[i];
      const cmdMap = regenerateCommandIds(clone);
      ampsCmdIds.push([...cmdMap.values()][0] ?? null);
      setIdList(docs.points, findDirectChild(clone, "incominglegs"), i > 0 ? [legIds[i - 1]] : []);
      setIdList(
        docs.points,
        findDirectChild(clone, "outgoinglegs"),
        i < ampsPoints.length - 1 ? [legIds[i]] : [],
      );
      const coordValueEl = findCoordinateValueEl(clone);
      if (coordValueEl) coordValueEl.textContent = formatCoordinate(point.lat, point.lon);
      setItemValue(clone, "PtNum", i + 1);
      setItemValue(clone, "DtdID", ampsName(point, i));
      setItemValue(clone, "PtNameFix", ampsName(point, i));
      setItemValue(clone, "PtDesc", ampsName(point, i));
      setItemValue(clone, "PtType", PT_TYPE_VALUES[point.ptType] || PT_TYPE_VALUES.turn);
      setItemValue(clone, "PtDbLookup", "");
      setItemValue(clone, "PtDesc", "");

      // Planned altitude / elevation / clock time. AMPS recomputes the
      // derived values on Calc, but honors these as the plan inputs.
      const planPoint = planResult.points[i];
      if (planPoint) {
        setItemValue(
          clone,
          "PlanAltitudeValue",
          formatAmpsPlanAltitude(planPoint.value, planPoint.ref),
        );
        if (planPoint.mslFt != null) {
          setItemValue(clone, "CmdAlt", formatAmpsCmdAlt(planPoint.mslFt));
          setItemValue(clone, "CmdAltValid", "True");
        }
        if (planPoint.groundFt != null) {
          setItemValue(clone, "Elevation", formatAmpsElevation(planPoint.groundFt));
        }
        if (planPoint.clockTime) {
          setItemValue(clone, "CmdClockTime", formatAmpsClockTime(planPoint.clockTime));
        }
      }
      pointsRoot.appendChild(clone);
    });

    // --- points.xml: serpentine calc points (display copies) ---
    legShaping.forEach((shaping, legIdx) => {
      shaping.forEach((point, i) => {
        const clone = calcPointProto.cloneNode(true);
        findDirectChild(clone, "id").textContent = calcIdsPerLeg[legIdx][i];
        regenerateCommandIds(clone);
        const coordValueEl = findCoordinateValueEl(clone);
        if (coordValueEl) coordValueEl.textContent = formatCoordinate(point.lat, point.lon);
        setItemValue(clone, "PtNameFix", `.Serpentine ${i + 1}`);
        setItemValue(clone, "Elevation", "0 m User");
        setItemValue(clone, "MSLAltitude", "0 MM");
        pointsRoot.appendChild(clone);
      });
    });

    // --- legs.xml ---
    legIds.forEach((legId, i) => {
      const clone = legProto.cloneNode(true);
      findDirectChild(clone, "id").textContent = legId;
      setIdList(docs.legs, findDirectChild(clone, "segments"), [segmentId]);
      setDirectChildText(clone, "startpt", ampsIds[i]);
      setDirectChildText(clone, "endpt", ampsIds[i + 1]);
      regenerateCommandIds(clone);

      // Planned leg airspeed and winds (the CmdStdLeg inputs AMPS plans with).
      // Both are the "to" values of the leg's arrival point.
      const planLeg = planResult.legs[i];
      if (planLeg) {
        setItemValue(clone, "AirspeedValue", formatAmpsAirspeed(planLeg.airspeed));
        const wind = formatAmpsWind(
          planLeg.wind?.dirTrue ?? 0,
          planLeg.wind?.speedKts ?? 0,
        );
        setItemValue(clone, "CruiseWind", wind);
        setItemValue(clone, "ClimbDescentWind", wind);
      }

      const shaping = legShaping[i];
      const trackpointset = findDirectChild(findDirectChild(clone, "commandbase"), "trackpointset");
      clearChildren(trackpointset);

      if (shaping.length > 0) {
        for (const point of shaping) {
          const item = trackpointItemProto.cloneNode(true);
          const tpId = crypto.randomUUID();
          findDirectChild(item, "key").textContent = tpId;
          const tp = findDirectChild(item, "trackpoint");
          findDirectChild(tp, "id").textContent = tpId;
          setItemValue(tp, "TrackPtCoordinate", formatCoordinate(point.lat, point.lon));
          setItemValue(tp, "TrackPtElv", "0 m User");
          trackpointset.appendChild(item);
        }
      } else {
        removeCommand(clone, "CmdSerpentine");
      }

      legCmdInfo.push({
        stdId: findCommandByType(clone, "CmdStdLeg").id,
        serpId: shaping.length > 0 ? findCommandByType(clone, "CmdSerpentine").id : null,
        shapingCount: shaping.length,
      });

      legsRoot.appendChild(clone);
    });

    // --- segments.xml ---
    {
      const clone = segmentProto.cloneNode(true);
      findDirectChild(clone, "id").textContent = segmentId;
      setIdList(docs.segments, findDirectChild(clone, "routes"), [routeId]);
      setIdList(docs.segments, findDirectChild(clone, "legs"), legIds);
      const refpoint = findDirectChild(clone, "refpoint");
      if (refpoint) setDirectChildText(refpoint, "id", crypto.randomUUID());
      segmentsRoot.appendChild(clone);
    }

    // --- routes.xml ---
    {
      const clone = routeProto.cloneNode(true);
      findDirectChild(clone, "id").textContent = routeId;
      setIdList(docs.routes, findDirectChild(clone, "segments"), [segmentId]);
      setItemValue(clone, "RouteName", sketch.name);
      routesRoot.appendChild(clone);
    }

    // --- missionsummary.xml ---
    {
      const clone = summaryRouteProto.cloneNode(true);
      findDirectChild(clone, "id").textContent = routeId;
      setDirectChildText(clone, "name", sketch.name);
      const summarySegment = clone.getElementsByTagName("segment")[0];
      if (summarySegment) setDirectChildText(summarySegment, "id", segmentId);
      summaryRoutesRoot.appendChild(clone);
    }

    // --- mission.gpx: rte with amps rtepts + serpentine rtepts in order ---
    {
      const rteClone = rteProto.cloneNode(true);
      setDirectChildText(rteClone, "name", sketch.name);
      const msnxRoute = rteClone.getElementsByTagName("msnx:route")[0];
      if (msnxRoute) msnxRoute.setAttributeNS(MSNX_NS, "msnx:name", sketch.name);
      const msnxSegment = rteClone.getElementsByTagName("msnx:segment")[0];
      if (msnxSegment) msnxSegment.setAttributeNS(MSNX_NS, "msnx:id", segmentId);

      for (const rtept of Array.from(rteClone.getElementsByTagName("rtept"))) {
        rtept.parentNode.removeChild(rtept);
      }

      const appendRtept = (proto, point, pointId, name, cmdId) => {
        const clone = proto.cloneNode(true);
        clone.setAttribute("lat", String(point.lat));
        clone.setAttribute("lon", String(point.lon));
        setDirectChildText(clone, "ele", String(point.ele ?? 0));
        setDirectChildText(clone, "name", name);
        setDirectChildText(clone, "desc", "");
        const msnxPoint = clone.getElementsByTagName("msnx:point")[0];
        if (msnxPoint) msnxPoint.setAttributeNS(MSNX_NS, "msnx:id", pointId);
        if (cmdId) {
          const cmd = clone.getElementsByTagName("msnx:command")[0];
          if (cmd) cmd.setAttributeNS(MSNX_NS, "msnx:id", cmdId);
        }
        rteClone.appendChild(clone);
      };

      ampsPoints.forEach((point, i) => {
        // Ground elevation (m) in the gpx when the plan calc fetched one.
        const groundFt = planResult.points[i]?.groundFt;
        const pointWithEle =
          groundFt != null ? { ...point, ele: groundFt * FT_TO_M } : point;
        appendRtept(rteptAmpsProto, pointWithEle, ampsIds[i], ampsName(point, i), ampsCmdIds[i]);
        if (i < legShaping.length) {
          legShaping[i].forEach((sp, j) => {
            appendRtept(rteptCalcProto, sp, calcIdsPerLeg[i][j], `.Serpentine ${j + 1}`, null);
          });
        }
      });

      gpxRoot.appendChild(rteClone);
    }

    // --- gpx root manifest: segment entry + leg entries ---
    {
      const segEntry = manifestSegmentProto.cloneNode(true);
      segEntry.setAttributeNS(MSNX_NS, "msnx:id", segmentId);
      const legList = segEntry.getElementsByTagName("msnx:legs")[0];
      clearChildren(legList);
      for (const legId of legIds) {
        const legRef = docs.gpx.createElementNS(MSNX_NS, "msnx:leg");
        legRef.setAttributeNS(MSNX_NS, "msnx:id", legId);
        legList.appendChild(legRef);
      }
      manifestSegmentsEl.appendChild(segEntry);

      legIds.forEach((legId, i) => {
        const legEntry = manifestLegProto.cloneNode(true);
        legEntry.setAttributeNS(MSNX_NS, "msnx:id", legId);
        const startEl = legEntry.getElementsByTagName("msnx:startpoint")[0];
        if (startEl) startEl.setAttributeNS(MSNX_NS, "msnx:id", ampsIds[i]);
        const endEl = legEntry.getElementsByTagName("msnx:endpoint")[0];
        if (endEl) endEl.setAttributeNS(MSNX_NS, "msnx:id", ampsIds[i + 1]);

        const { stdId, serpId, shapingCount } = legCmdInfo[i];
        const commandsEl = legEntry.getElementsByTagName("msnx:commands")[0];
        for (const cmd of Array.from(commandsEl.getElementsByTagName("msnx:command"))) {
          const type = cmd.getAttribute("msnx:type");
          if (type === "CmdStdLeg" && stdId) {
            cmd.setAttributeNS(MSNX_NS, "msnx:id", stdId);
          } else if (type === "CmdSerpentine") {
            if (serpId) cmd.setAttributeNS(MSNX_NS, "msnx:id", serpId);
            else commandsEl.removeChild(cmd);
          }
        }
        const tracksEl = commandsEl.getElementsByTagName("msnx:tracks")[0];
        for (const ph of Array.from(commandsEl.getElementsByTagName("msnx:point"))) {
          commandsEl.removeChild(ph);
        }
        if (shapingCount > 0 && manifestPointPlaceholderProto) {
          for (let n = 0; n < shapingCount; n++) {
            commandsEl.appendChild(manifestPointPlaceholderProto.cloneNode(true));
          }
        } else if (tracksEl && shapingCount === 0) {
          commandsEl.removeChild(tracksEl);
        }

        manifestLegsEl.appendChild(legEntry);
      });
    }
  }

  // --- mission.xml: point the mission's focus/primary ids at the first route ---
  const missionEl = docs.mission.getElementsByTagName("mission")[0];
  setItemValue(missionEl, "MissionName", missionName);
  setItemValue(missionEl, "FocusRoute", firstIds.routeId);
  setItemValue(missionEl, "PrimaryRoute", firstIds.routeId);
  setItemValue(missionEl, "FocusSegment", firstIds.segmentId);
  setItemValue(missionEl, "FocusRtePt", firstIds.pointId);

  // The gpx root's extensions block also carries the mission name.
  const gpxMission = gpxRoot.getElementsByTagName("msnx:mission")[0];
  if (gpxMission) gpxMission.setAttributeNS(MSNX_NS, "msnx:name", missionName);

  // --- serialize (mission/*.xml carry a UTF-8 BOM; mission.gpx does not) ---
  const noFolders = { createFolders: false };
  const decl = '<?xml version="1.0" encoding="utf-8"?>';
  zip.file(TEMPLATE_PATHS.gpx, serializeXmlPart(docs.gpx, { decl: '<?xml version="1.0"?>' }));
  for (const key of ["points", "legs", "segments", "routes", "mission", "missionsummary"]) {
    zip.file(TEMPLATE_PATHS[key], serializeXmlPart(docs[key], { bom: true, decl }), noFolders);
  }

  return zip;
}

/** Fetches the bundled template, builds the sketch .msnx, and downloads it. */
export async function buildSketchMsnx(sketchedRoutes, filename = sketchedRoutes.map(route => route.name).join("_") + ".msnx") {
  const res = await fetch(TEMPLATE_URL);
  if (!res.ok) {
    throw new Error("Couldn't load the mission template (msnx_template.msnx).");
  }
  const zip = await buildSketchMsnxZip(
    await res.arrayBuffer(),
    sketchedRoutes,
    filename.replace(/\.msnx$/i, ""),
  );

  const blob = await zip.generateAsync({
    type: "blob",
    compression: "DEFLATE",
    compressionOptions: { level: 6 },
  });
  downloadBlob(blob, filename);
}
