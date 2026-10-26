/*
 * What a brief is built from, in the shape compileBrief reads (feature/brief/compile.js): the LZ/PZs as {id, name,
 * diagram} and the route sets as {id, name, data: {version, routes}}. In a pack, everything in it (the design: "Built
 * from everything in OP DK"); outside one, the LZ/PZs and route sets open in this session. An id is what the brief's
 * flow refers to an item by ("lz:<id>", "route:<id>/<route id>"), so it must be the one the item keeps.
 */

/** The plan from a pack's live items (useMissionPack's `items`): its LZ/PZs and route sets, by item uuid. */
export const planFromPack = (packName, items) => {
  const live = (items ?? []).filter((item) => !item.deleted && item.data);
  return {
    name: packName ?? "",
    lzs: live.filter((item) => item.kind === "lz").map((item) => ({ id: item.uuid, name: item.name, diagram: item.data })),
    routeSets: live
      .filter((item) => item.kind === "route")
      .map((item) => ({ id: item.uuid, name: item.name, data: item.data })),
  };
};

/**
 * The plan from this session: the LZ/PZs open (by diagram id) and the route sets open (by their key: a saved set's id,
 * the sketches' own key, or a mission file's). A mission file's routes are read as they are drawn on the map.
 */
export const planFromSession = (name, diagrams, routeSets) => ({
  name: name ?? "",
  lzs: (diagrams ?? []).filter((d) => d?.id).map((d) => ({ id: String(d.id), name: d.name || "", diagram: d })),
  routeSets: (routeSets ?? [])
    .filter((set) => Array.isArray(set?.routes) && set.routes.length > 0)
    .map((set) => ({ id: String(set.key), name: set.fileName || set.name || "", data: { version: 1, routes: set.routes } })),
});

/** "5 LZ/PZs, 2 route sets, 1 threat": what the brief is built from, as the menu says it. */
export const planSummary = (plan, threatCount = 0) => {
  const count = (n, one, many) => `${n} ${n === 1 ? one : many}`;
  const parts = [count(plan.lzs.length, "LZ/PZ", "LZ/PZs"), count(plan.routeSets.length, "route set", "route sets")];
  if (threatCount > 0) parts.push(count(threatCount, "threat", "threats"));
  return parts.join(", ");
};

/** The local date (YYYY-MM-DD) of an instant, and the offset east of UTC in minutes there, as this device keeps time. */
export const localClock = (ms) => {
  const d = new Date(ms);
  const pad = (n) => String(n).padStart(2, "0");
  return { date: `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`, utcOffsetMinutes: -d.getTimezoneOffset() };
};

/** A brief document with nothing answered yet (docs/MISSION_BRIEF.md §2.2). */
export const newBriefDocument = (templateId, templateVersion) => ({
  version: 1,
  template: { id: templateId, version: templateVersion },
  answers: {},
});
