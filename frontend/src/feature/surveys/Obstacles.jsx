import React, { useId } from "react";

/*
 * What the LiDAR found standing on and round the LZ (the snapshot's obstacles, docs/LZ_SURVEYS.md §4): the clearance along the best
 * approach and the best exit, from the LZ's edge as the page prints it and from the touchdown point beside it (an approach is flown to
 * the touchdown point; §11 R5 prints the edge's), the tallest in the LZ and within 100 m of it, what stands over 2 m in it and its
 * trees; or why there is nothing, in the server's words. Every number names its survey, and the page says what the LiDAR cannot see.
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

const feet = (value) => `${Math.round(value).toLocaleString()} ft`;
const metres = (value) => `${Math.round(value).toLocaleString()} m`;
const headingWords = (value) => `${String(Math.round(value) % 360 || 360).padStart(3, "0")}°`;
const angle = (value) => `${value.toFixed(1)}°`;

/** A point the LiDAR found, from the touchdown point: "39 ft tree, 36 m at 034°". */
export const spotWords = (spot) =>
  spot ? `${feet(spot.heightFt)} ${KIND[spot.kind] ?? "obstacle"}, ${metres(spot.distanceM)} at ${headingWords(spot.bearingDeg)}` : null;

/** The obstacle that sets a way's clearance, from the LZ's edge: "98 ft tree 198 m out, 4 m right of the track". */
export const obstacleWords = (obstacle) => {
  if (!obstacle) return null;
  const out = `${metres(obstacle.fromEdgeM ?? obstacle.distanceM)} out`;
  const side = Number.isFinite(obstacle.offsetM) && Math.abs(obstacle.offsetM) >= 1
    ? `, ${metres(Math.abs(obstacle.offsetM))} ${obstacle.offsetM > 0 ? "right" : "left"} of the track`
    : "";
  return obstacle.kind === "terrain" ? `The ground ${out}${side}` : `${feet(obstacle.heightFt)} ${KIND[obstacle.kind] ?? "obstacle"} ${out}${side}`;
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
  return (
    <tr>
      <th scope="row">{label} {headingWords(corridor.headingDeg)}</th>
      <td>{clear ? angle(clear.angleDeg) : "—"}</td>
      <td>{clear ? (clear.angleDeg <= 0 ? "Nothing above the edge" : obstacleWords(clear.obstacle)) : "No LiDAR returns in its corridor"}</td>
      <td>{corridor.fromTarget ? angle(corridor.fromTarget.angleDeg) : "—"}</td>
    </tr>
  );
};

const Obstacles = ({ obstacles }) => {
  const headingId = useId();
  if (!obstacles) return null;
  const survey = obstacles.survey ? `${obstacles.survey.name}${obstacles.survey.year && !String(obstacles.survey.name).includes(String(obstacles.survey.year)) ? ` (${obstacles.survey.year})` : ""}` : null;
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
          <dl className="survey-facts">
            <div><dt>Tallest in the LZ</dt><dd>{spotWords(obstacles.inside) ?? "Nothing standing found"}</dd></div>
            <div>
              <dt>Tallest within 100 m</dt>
              <dd>
                {spotWords(obstacles.around) ?? "Nothing standing found"}
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
            Angles clear the highest return with no margin. These are the tallest things found in the LiDAR, not every obstacle: trees
            have grown or fallen since it was flown, wires and poles seldom show in it, and treetops read up to a metre low.
          </p>
        </>
      )}
    </div>
  );
};

export default Obstacles;
