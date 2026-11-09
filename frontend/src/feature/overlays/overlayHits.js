/*
 * Which drawn feature of the overlays a click on the map was on. Overlays take no mouse events of
 * their own, so a planning graphic under or over one is never blocked by it: the map's own click is
 * measured against them afterwards, in pixels. A polygon counts only on its edge, because a wide
 * shaded area would otherwise answer every click inside it.
 */

const POINT_PX = 12;
const LINE_PX = 8;

const distanceToSegment = (p, a, b) => {
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  const lengthSquared = dx * dx + dy * dy;
  const t = lengthSquared === 0 ? 0 : Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquared));
  return Math.hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy));
};

const distanceToPath = (click, path, project) => {
  let best = Infinity;
  let previous = null;
  path.forEach(([lat, lon]) => {
    const current = project(lat, lon);
    if (previous) best = Math.min(best, distanceToSegment(click, previous, current));
    previous = current;
  });
  return best;
};

// bbox: [south, west, north, east]. Most features are nowhere near a click, and this costs two projections.
const nearBox = (click, bbox, project, reach) => {
  const a = project(bbox[0], bbox[1]);
  const b = project(bbox[2], bbox[3]);
  return (
    click.x >= Math.min(a.x, b.x) - reach &&
    click.x <= Math.max(a.x, b.x) + reach &&
    click.y >= Math.min(a.y, b.y) - reach &&
    click.y <= Math.max(a.y, b.y) + reach
  );
};

/**
 * The nearest feature within reach of `click` ({x, y}, the frame `project(lat, lon)` answers in), a
 * point before a line before a polygon's edge, the overlay drawn last before those under it. Null if none.
 */
export const featureAt = (overlays, click, project) => {
  for (let index = overlays.length - 1; index >= 0; index -= 1) {
    let best = null;
    let bestRank = Infinity;
    let bestDistance = Infinity;
    overlays[index].doc.features.forEach((feature) => {
      const point = feature.kind === "point";
      const reach = point ? POINT_PX : LINE_PX;
      if (!nearBox(click, feature.bbox, project, reach)) return;
      let distance;
      if (point) {
        const at = project(feature.at[0], feature.at[1]);
        distance = Math.hypot(click.x - at.x, click.y - at.y);
      } else if (feature.kind === "line") {
        distance = distanceToPath(click, feature.path, project);
      } else {
        distance = Math.min(...feature.rings.map((ring) => distanceToPath(click, ring, project)));
      }
      if (distance > reach) return;
      const rank = point ? 0 : feature.kind === "line" ? 1 : 2;
      if (rank < bestRank || (rank === bestRank && distance < bestDistance)) {
        best = feature;
        bestRank = rank;
        bestDistance = distance;
      }
    });
    if (best) return best;
  }
  return null;
};
