import React, { useId } from "react";

/*
 * What the LiDAR found standing on and round the LZ (the snapshot's obstacles, docs/LZ_SURVEYS.md §4): the clearance along the best
 * approach and the best exit, from the LZ's edge as the page prints it and from the touchdown point beside it (an approach is flown to
 * the touchdown point; §11 R5 prints the edge's), the tallest in the LZ and within 100 m of it, what stands over 2 m in it and its
 * trees; or why there is nothing, in the server's words. An obstacle is what stands 2 m or more above its own ground, and the ground
 * itself where it rises (survey/obstacles.py). Every number names its survey, and the page says what the LiDAR cannot see.
 */

const KIND = {
  vegetation: "tree",
  building: "building",
  tower: "tower",
  wire: "wire",
  bridge: "bridge",
  unclassified: "obstacle (unclassified)",
  other: "obstacle",
  terrain: "the ground",
};
// Vegetation under a tree's 5 m (survey/obstacles.py's TREE_MIN_M) is brush, as the page says it.
const TREE_MIN_FT = 16.4;
// Why a way has no angle (survey/obstacles.py's ``missing``).
const MISSING = {
  no_returns: "No LiDAR returns in its corridor",
  no_edge_ground: "No ground at the LZ's edge in the LiDAR",
};

const feet = (value) => `${Math.round(value).toLocaleString()} ft`;
const metres = (value) => `${Math.round(value).toLocaleString()} m`;
const headingWords = (value) => `${String(Math.round(value) % 360 || 360).padStart(3, "0")}°`;
const angle = (value) => `${Math.max(value, 0).toFixed(1)}°`;
const kindWords = (thing) =>
  thing.kind === "vegetation" && !(thing.heightFt >= TREE_MIN_FT) ? "brush" : KIND[thing.kind] ?? "obstacle";

/** A point the LiDAR found, from the touchdown point: "39 ft tree, 36 m at 034°". */
export const spotWords = (spot) =>
  spot ? `${feet(spot.heightFt)} ${kindWords(spot)}, ${metres(spot.distanceM)} at ${headingWords(spot.bearingDeg)}` : null;

/**
 * The obstacle that sets a way's clearance, from the LZ's edge: "98 ft tree 198 m out, 4 m right of the track". Where its top stands
 * higher or lower over the edge's ground than it stands over its own (ground rising or falling away from the LZ), that too: the angle
 * is to that height.
 */
export const obstacleWords = (obstacle) => {
  if (!obstacle) return null;
  const out = `${metres(obstacle.fromEdgeM ?? obstacle.distanceM)} out`;
  const side = Number.isFinite(obstacle.offsetM) && Math.abs(obstacle.offsetM) >= 1
    ? `, ${metres(Math.abs(obstacle.offsetM))} ${obstacle.offsetM > 0 ? "right" : "left"} of the track`
    : "";
  const rise = Number.isFinite(obstacle.riseFt) ? obstacle.riseFt : null;
  if (obstacle.kind === "terrain") {
    return `The ground ${out}${rise !== null ? `, ${feet(rise)} above the edge` : ""}${side}`;
  }
  const over = rise !== null && Math.abs(rise - obstacle.heightFt) >= 5 ? ` (${feet(rise)} above the edge)` : "";
  return `${feet(obstacle.heightFt)} ${kindWords(obstacle)} ${out}${over}${side}`;
};

const Way = ({ label, which, corridor }) => {
  if (!corridor) {
    return (
      <tr>
        <th scope="row">{label}</th>
        <td colSpan={3} className="survey-muted">Not measured: set the best {which} and measure again.</td>
      </tr>
    );
  }
  const clear = corridor.clearance;
  let what = MISSING[corridor.missing] ?? MISSING.no_returns;
  if (clear) what = clear.obstacle && clear.angleDeg > 0 ? obstacleWords(clear.obstacle) : "Nothing above the edge";
  return (
    <tr>
      <th scope="row">{label} {headingWords(corridor.headingDeg)}</th>
      <td>{clear ? angle(clear.angleDeg) : "—"}</td>
      <td>{what}</td>
      <td>{corridor.fromTarget ? angle(corridor.fromTarget.angleDeg) : "—"}</td>
    </tr>
  );
};

/** How much of a way's corridor the survey has returns in, where it is not all of it: an angle over a part is not the corridor's. */
const coverageWords = (label, corridor) => {
  const steps = corridor?.bins?.length;
  if (!steps || !Number.isInteger(corridor.covered) || corridor.covered >= steps) return null;
  const step = corridor.binM ?? 10;
  return `LiDAR over ${metres(corridor.covered * step)} of the ${label}'s ${metres(steps * step)}: its angle is over that part.`;
};

const Obstacles = ({ obstacles }) => {
  const headingId = useId();
  if (!obstacles) return null;
  const survey = obstacles.survey ? `${obstacles.survey.name}${obstacles.survey.year && !String(obstacles.survey.name).includes(String(obstacles.survey.year)) ? ` (${obstacles.survey.year})` : ""}` : null;
  const partial = [coverageWords("approach", obstacles.approach), coverageWords("exit", obstacles.exit)].filter(Boolean);
  return (
    <div className="survey-obstacles" aria-labelledby={headingId} role="group">
      <h4 id={headingId} className="survey-obstacles__title">Standing on and round the LZ{survey ? ` · LiDAR ${survey}` : ""}</h4>
      {obstacles.status !== "measured" ? (
        <p className={obstacles.status === "failed" ? "survey-state survey-state--warn" : "survey-muted"}>{obstacles.words}</p>
      ) : (
        <>
          <table className="survey-table survey-table--compact">
            <thead>
              <tr>
                <th scope="col">Way</th>
                <th scope="col">Clears at, from the LZ&apos;s edge</th>
                <th scope="col">What sets it</th>
                <th scope="col">From the touchdown point</th>
              </tr>
            </thead>
            <tbody>
              <Way label="Approach" which="approach" corridor={obstacles.approach} />
              <Way label="Exit" which="exit" corridor={obstacles.exit} />
            </tbody>
          </table>
          {partial.map((words) => <p key={words} className="survey-state survey-state--warn">{words}</p>)}
          <dl className="survey-facts">
            <div><dt>Tallest in the LZ</dt><dd>{spotWords(obstacles.inside) ?? "Nothing standing 2 m or more"}</dd></div>
            <div>
              <dt>Tallest within 100 m</dt>
              <dd>
                {spotWords(obstacles.around) ?? "Nothing standing 2 m or more"}
                {obstacles.around && <div className="survey-muted">{metres(obstacles.around.fromEdgeM)} outside the boundary</div>}
              </dd>
            </div>
            <div><dt>Over 2 m in the LZ</dt><dd>{(obstacles.standing?.count ?? 0).toLocaleString()} LiDAR points</dd></div>
            <div>
              <dt>Trees in the LZ</dt>
              <dd>
                {obstacles.trees?.length
                  ? `${obstacles.trees.length} of 5 m or more, the tallest ${feet(obstacles.trees[0].heightFt)}`
                  : "None of 5 m or more"}
              </dd>
            </div>
          </dl>
          <p className="survey-muted">
            Angles clear the steepest obstacle with no margin: what stands 2 m or more above its ground, and the ground where it rises.
            These are the tallest things found in the LiDAR, not every obstacle: trees have grown or fallen since it was flown, wires and
            poles seldom show in it, and treetops read up to a metre low.
          </p>
        </>
      )}
    </div>
  );
};

export default Obstacles;
