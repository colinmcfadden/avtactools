import React, { useId, useState } from "react";
import { AngleDialog } from "./Disagreements";

/*
 * What the LiDAR found standing on and round the LZ (the snapshot's obstacles, docs/LZ_SURVEYS.md §4), and each way as the page plans it
 * (the survey's ``plans``, survey/flightpath.py): along the best approach and the best exit, from the touchdown point, the angle planned
 * (30° where what stands allows it, else the shallowest that clears it, said to be steeper) and where its path is the way's height above
 * the highest obstacle for good (the approach's descent from the survey's height begins there; the exit has climbed to 100 ft), with
 * how high above the ground that is; beside it the shallowest angle that clears what stands in a lane 10 m each side of the track, and
 * what sets it; the tallest in the LZ and within 100 m of it, what stands over 2 m in it and its trees; or why there is nothing, in the
 * server's words. In the way is what stands 5 m or more above its own ground (2 m for what is built) and the ground where it rises
 * (survey/obstacles.py). The editor can type the approach's angle in place of the plan: the page prints theirs, and the LiDAR's stays
 * here beside it. Every number names its survey, and the page says what the LiDAR cannot see.
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
// Each way is planned at this where what stands allows it (survey/flightpath.py's PLAN_DEG).
const PLAN_DEG = 30;
// Why a way has no angle (survey/obstacles.py's ``missing``).
const MISSING = {
  no_returns: "No LiDAR returns in its lane",
  no_target_ground: "No ground at the touchdown point in the LiDAR",
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
 * What sets a way's angle, from the touchdown point: "98 ft tree 200 m out, 4 m right of the track". Where its top stands higher or
 * lower over the touchdown point's ground than it stands over its own (ground rising or falling away from the LZ), that too: the
 * angle is to that height.
 */
export const obstacleWords = (obstacle) => {
  if (!obstacle) return null;
  const out = `${metres(obstacle.fromTargetM ?? obstacle.distanceM)} out`;
  const side = Number.isFinite(obstacle.offsetM) && Math.abs(obstacle.offsetM) >= 1
    ? `, ${metres(Math.abs(obstacle.offsetM))} ${obstacle.offsetM > 0 ? "right" : "left"} of the track`
    : "";
  const rise = Number.isFinite(obstacle.riseFt) ? obstacle.riseFt : null;
  if (obstacle.kind === "terrain") {
    return `The ground ${out}${rise !== null ? `, ${feet(rise)} above the touchdown point` : ""}${side}`;
  }
  const over = rise !== null && Math.abs(rise - obstacle.heightFt) >= 5 ? ` (${feet(rise)} above the touchdown point)` : "";
  return `${feet(obstacle.heightFt)} ${kindWords(obstacle)} ${out}${over}${side}`;
};

/**
 * Where a way's planned path is its height above the highest obstacle for good, and how high above the ground that is: "From 100 ft
 * AHO: 405 m out, 198 ft above the ground" (the approach's descent begins there), "To 100 ft AHO: …" (the exit has climbed to it);
 * "past 1,000 m" where it is still within that height of what stands at the lane's last step.
 */
export const descentWords = (plan, exit = false) => {
  const descent = plan?.descent;
  if (!descent) return "—";
  const height = `${exit ? "To" : "From"} ${feet(plan.heightFt)} AHO`;
  if (!Number.isFinite(descent.fromTargetM)) return `${height}: past ${metres(descent.pastM ?? 1000)}`;
  const above = Number.isFinite(descent.topFt) && Number.isFinite(descent.groundFt)
    ? descent.topFt + plan.heightFt - descent.groundFt
    : null;
  return `${height}: ${metres(descent.fromTargetM)} out${above !== null ? `, ${feet(above)} above the ground` : ""}`;
};

const Way = ({ label, which, lane, plan }) => {
  if (!lane || !lane.start) {
    return (
      <tr>
        <th scope="row">{label}{lane ? ` ${headingWords(lane.headingDeg)}` : ""}</th>
        <td colSpan={3} className="survey-muted">
          {lane ? "Measured by an earlier EZ/PZ: measure the LZ again." : `Not measured: set the best ${which} and measure again.`}
        </td>
      </tr>
    );
  }
  const clear = lane.clearance;
  let what = MISSING[lane.missing] ?? MISSING.no_returns;
  if (clear) what = clear.obstacle && clear.angleDeg > 0 ? obstacleWords(clear.obstacle) : "Nothing in the way";
  return (
    <tr>
      <th scope="row">{label} {headingWords(lane.headingDeg)}</th>
      <td>
        {plan ? angle(plan.angleDeg) : "—"}
        {plan?.steep && <div className="survey-state survey-state--warn">Steeper than {PLAN_DEG}°: nothing less clears it</div>}
        {plan?.typed && <div className="survey-muted">Yours</div>}
      </td>
      <td>{plan ? descentWords(plan, which === "exit") : "—"}</td>
      <td>{clear ? <><strong>{angle(clear.angleDeg)}</strong>: {what}</> : what}</td>
    </tr>
  );
};

/** How much of a way's lane the survey has returns in, where it is not all of it: an angle over a part is not the lane's. */
const coverageWords = (label, lane) => {
  const steps = lane?.bins?.length;
  if (!steps || !Number.isInteger(lane.covered) || lane.covered >= steps) return null;
  const step = lane.binM ?? 10;
  return `LiDAR over ${metres(lane.covered * step)} of the ${label}'s ${metres(steps * step)}: its angle is over that part.`;
};

/** The approach's angle the page prints where the editor typed it in place of the plan, with ways to change it or let it go. */
const Printed = ({ row, busy, onType, onUndecide }) => {
  const typed = row?.override?.choice === "typed" ? row.override : null;
  if (!typed && !row?.computed) return null;
  return (
    <div className="survey-obstacles__printed">
      {typed && (
        <p className={`survey-state survey-state--${row.overrideStands ? "info" : "warn"}`}>
          {row.overrideStands
            ? `The page prints ${angle(typed.value)} for the approach, typed by ${typed.by || "an editor"}: “${typed.reason}”`
            : `Your ${angle(typed.value)} was typed against other values and is no longer printed: look again.`}
        </p>
      )}
      {onType && (
        <div className="survey-actions">
          <button type="button" className="ui-btn ui-btn--28" disabled={busy || !row.computed} onClick={onType}>
            {typed ? "Change your angle…" : "Type the approach's angle…"}
          </button>
          {typed && onUndecide && (
            <button type="button" className="ui-btn ui-btn--28 ui-btn--ghost" disabled={busy} onClick={() => onUndecide(row.key)}>
              Print the plan
            </button>
          )}
        </div>
      )}
    </div>
  );
};

const Obstacles = ({ obstacles, plans = null, heightDirty = false, approach = null, onDecide, onUndecide, busy = false }) => {
  const headingId = useId();
  const [typing, setTyping] = useState(false);
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
                <th scope="col">Planned</th>
                <th scope="col">Descent / climb</th>
                <th scope="col">Shallowest clear</th>
              </tr>
            </thead>
            <tbody>
              <Way label="Approach" which="approach" lane={obstacles.approach} plan={plans?.approach} />
              <Way label="Exit" which="exit" lane={obstacles.exit} plan={plans?.exit} />
            </tbody>
          </table>
          {heightDirty && <p className="survey-muted">Save to plan the approach from the height you have typed.</p>}
          <Printed row={approach} busy={busy} onType={onDecide ? () => setTyping(true) : null} onUndecide={onUndecide} />
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
            Each way is planned at {PLAN_DEG}° where what stands allows it, else at the shallowest angle that clears it. Angles are from
            the touchdown point, with no margin, over everything in a lane 10 m each side of the track: what stands 5 m or more above
            its ground (2 m for a building, a wire, a tower or a bridge), and the ground where it rises. The approach is flown at the
            survey's height above the highest obstacle (AHO), the treetops or the ground where nothing stands, until its descent (100 ft
            where the survey gives none); the exit climbs to 100 ft above it. These are the tallest things found in the LiDAR, not every
            obstacle: trees have grown or fallen since it was flown, wires and poles seldom show in it, and treetops read up to a metre
            low.
          </p>
        </>
      )}
      {typing && approach && (
        <AngleDialog
          row={approach}
          plan={plans?.approach ?? null}
          onCancel={() => setTyping(false)}
          onConfirm={async (value, reason) => {
            const saved = await onDecide(approach.key, "typed", reason, value);
            if (saved) setTyping(false);
            return Boolean(saved);
          }}
        />
      )}
    </div>
  );
};

export default Obstacles;
