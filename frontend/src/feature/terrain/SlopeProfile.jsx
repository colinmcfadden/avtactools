import React, { useId, useState } from "react";
import Icon from "../ui/Icon";
import "./slopeProfile.css";

// The graph's own units: it is drawn in a 300 by 86 box and stretched to the tile's width (so its words and the helicopter are HTML over
// it, which is not stretched). A unit down is a pixel. The ground is drawn from TOP to BOTTOM, leaving room above for the helicopter.
const W = 300;
const H = 86;
const TOP = 24;
const BOTTOM = 82;
const LEFT = 0;
const RIGHT = 300;
// Flat ground is not drawn as a cliff: the height axis spans at least this many feet.
const MIN_SPAN_FT = 30;

const scaleOf = (line) => {
  const known = line.elevationsFt.filter((h) => h !== null);
  const low = Math.min(...known);
  const high = Math.max(...known);
  const span = Math.max(high - low, MIN_SPAN_FT);
  const middle = (low + high) / 2;
  const [first, last] = [line.distancesFt[0], line.distancesFt[line.distancesFt.length - 1]];
  return {
    low: middle - span / 2,
    high: middle + span / 2,
    x: (d) => LEFT + ((d - first) / (last - first)) * (RIGHT - LEFT),
    y: (h) => BOTTOM - ((h - (middle - span / 2)) / span) * (BOTTOM - TOP),
  };
};

/** The ground as runs of points with heights: a gap where the terrain has none is a gap in the line, never a guess. */
const runsOf = (line) => {
  const runs = [];
  let run = [];
  line.elevationsFt.forEach((h, i) => {
    if (h === null) {
      if (run.length > 1) runs.push(run);
      run = [];
    } else {
      run.push([line.distancesFt[i], h]);
    }
  });
  if (run.length > 1) runs.push(run);
  return runs;
};

// The helicopter over each graph, in pixels: a UH-60's outline in side view (nose to the right, the way the approach flies) and from
// behind (the crew's left on the left). The same outlines are Android's (SlopeProfileTile.kt), path for path.
export const HELI_SIDE = {
  width: 28,
  height: 16,
  paths: [
    "M2 1.2H26V2.6H2Z",
    "M13.3 2.6H14.7V5H13.3Z",
    "M9 5H19.5C23 5 25.5 7.2 26 10C25.6 12 24 13 21 13H10C8.6 13 8 12 8 10.8V6C8 5.4 8.4 5 9 5Z",
    "M1 7.6L8.2 8L8.2 10.2L1 9.4Z",
    "M0.6 4.6L2.4 4.6L2.6 9.6L0.6 9.6Z",
    "M9.5 14.4H23V15.4H9.5Z",
    "M12 13H13V14.4H12Z",
    "M19.5 13H20.5V14.4H19.5Z",
  ],
};
export const HELI_REAR = {
  width: 26,
  height: 16,
  paths: [
    "M1 1.2H25V2.6H1Z",
    "M12.3 2.6H13.7V5H12.3Z",
    "M9.5 5H16.5C17.9 5 19 6.1 19 7.5V11C19 12.4 17.9 13.5 16.5 13.5H9.5C8.1 13.5 7 12.4 7 11V7.5C7 6.1 8.1 5 9.5 5Z",
    "M8.6 12.6L9.6 13.4L6.4 15.6L5.4 14.8Z",
    "M17.4 12.6L16.4 13.4L19.6 15.6L20.6 14.8Z",
  ],
};
// The outlines are drawn this much larger than their own units, and keep this far above the ground under them.
export const HELI_SCALE = 1.25;
const HELI_CLEAR = 2;
// The approach's helicopter is over the first part of the line; it keeps clear of the highest ground there.
const APPROACH_SHARE = 0.15;

/** The highest point of the ground (the smallest y) between distances [from] and [to], or of all of it when none is there. */
const highestY = (line, s, from, to) => {
  const within = line.elevationsFt.filter((h, i) => h !== null && line.distancesFt[i] >= from && line.distancesFt[i] <= to);
  const known = within.length > 0 ? within : line.elevationsFt.filter((h) => h !== null);
  return Math.min(...known.map((h) => s.y(h)));
};

/**
 * Where the helicopter goes over a graph, in pixels from the plot's top-left: on the approach end of the line along the landing heading
 * ("side", nose toward the LZ), or over the middle of the LZ on the line across it ("rear"); either way just clear of the highest ground
 * under it, never above the plot.
 */
export const heliPlacement = (line, s, view) => {
  const first = line.distancesFt[0];
  const last = line.distancesFt[line.distancesFt.length - 1];
  const art = view === "side" ? HELI_SIDE : HELI_REAR;
  const width = art.width * HELI_SCALE;
  const height = art.height * HELI_SCALE;
  if (view === "side") {
    const ground = highestY(line, s, first, first + (last - first) * APPROACH_SHARE);
    return { art, width, height, left: "2px", top: Math.max(0, ground - height - HELI_CLEAR) };
  }
  const [from, to] = line.lz ?? [-(last - first) * 0.05, (last - first) * 0.05];
  const middle = (from + to) / 2;
  const ground = highestY(line, s, from, to);
  const left = `calc(${((s.x(middle) / W) * 100).toFixed(2)}% - ${width / 2}px)`;
  return { art, width, height, left, top: Math.max(0, ground - height - HELI_CLEAR) };
};

const Helicopter = ({ placement, view }) => (
  <svg
    className={`slope-profile__heli slope-profile__heli--${view}`}
    viewBox={`0 0 ${placement.art.width} ${placement.art.height}`}
    width={placement.width}
    height={placement.height}
    style={{ left: placement.left, top: placement.top }}
    aria-hidden="true"
  >
    {placement.art.paths.map((d) => <path key={d} d={d} />)}
  </svg>
);

/**
 * One profile (profileLine's, with its words): the ground as a line over a shaded fill, the LZ as a band, its two ends named, the heights
 * of its top and bottom in a gutter to its left, and a helicopter ([view]: "side" on the approach, "rear" over the LZ) to say which way
 * the aircraft comes and faces.
 */
export const ProfileGraph = ({ line, startLabel, endLabel, className, view }) => {
  const known = line.elevationsFt.filter((h) => h !== null);
  if (known.length < 2) return null;
  const s = scaleOf(line);
  const runs = runsOf(line);
  const points = (run) => run.map(([d, h]) => `${s.x(d).toFixed(1)},${s.y(h).toFixed(1)}`).join(" ");
  return (
    <figure className={`slope-profile ${className ?? ""}`}>
      <figcaption className="slope-profile__words">{line.words}</figcaption>
      <div className="slope-profile__chart">
        <div className="slope-profile__gutter" aria-hidden="true">
          <span className="slope-profile__axis slope-profile__axis--high" style={{ top: TOP - 5 }}>{Math.round(s.high)}</span>
          <span className="slope-profile__axis slope-profile__axis--low" style={{ top: BOTTOM - 5 }}>{Math.round(s.low)}</span>
        </div>
        <div className="slope-profile__plot">
          <svg className="slope-profile__graph" viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" aria-hidden="true">
            {line.lz && (
              <rect
                className="slope-profile__lz"
                x={s.x(line.lz[0])} y={TOP} width={Math.max(0, s.x(line.lz[1]) - s.x(line.lz[0]))} height={BOTTOM - TOP}
              />
            )}
            {runs.map((run) => (
              <polygon
                key={`fill-${run[0][0]}`}
                className="slope-profile__ground"
                points={`${s.x(run[0][0]).toFixed(1)},${BOTTOM} ${points(run)} ${s.x(run[run.length - 1][0]).toFixed(1)},${BOTTOM}`}
              />
            ))}
            {runs.map((run) => <polyline key={`line-${run[0][0]}`} className="slope-profile__line" points={points(run)} />)}
          </svg>
          {view && <Helicopter placement={heliPlacement(line, s, view)} view={view} />}
        </div>
      </div>
      <div className="slope-profile__ends" aria-hidden="true"><span>{startLabel}</span><span>{endLabel}</span></div>
    </figure>
  );
};

/** Whether the tile is open is remembered in this browser (how this person likes the panel, not part of any plan); open until closed. */
export const SLOPE_PROFILE_STORAGE_KEY = "ezpz.slopeProfile";

const readOpen = () => {
  try {
    return window.localStorage.getItem(SLOPE_PROFILE_STORAGE_KEY) !== "closed";
  } catch {
    return true;
  }
};

const storeOpen = (open) => {
  try {
    if (open) window.localStorage.removeItem(SLOPE_PROFILE_STORAGE_KEY);
    else window.localStorage.setItem(SLOPE_PROFILE_STORAGE_KEY, "closed");
  } catch {
    // Not remembered across reloads; the button still works.
  }
};

/**
 * The slope profile: the ground along the landing heading (approach to departure, the LZ shaded) and across it (left to right), from
 * slopeProfileFor. Heights in feet; the height axis spans at least 30 ft, so gentle ground is not drawn as steep. It opens and closes from
 * a button drawn as the redesign's dropdowns are (the top bar's Import and workspace menus): bordered, with a chevron on its right that
 * points down while closed and up while open; open, the graphs sit in a box under it.
 */
const SlopeProfile = ({ profile }) => {
  const [open, setOpen] = useState(readOpen);
  const bodyId = useId();
  if (!profile) return null;
  const toggle = () => {
    setOpen(!open);
    storeOpen(!open);
  };
  return (
    <div className={`span-6 slope-profile-block${open ? " slope-profile-block--open" : ""}`}>
      <button
        type="button"
        className="slope-profile__toggle"
        aria-label="Slope profile, heights in feet"
        aria-expanded={open}
        aria-controls={bodyId}
        onClick={toggle}
      >
        <span className="slope-profile__toggle-label">Slope profile</span>
        <span className="slope-profile__toggle-unit">ft</span>
        <Icon name={open ? "chevronUp" : "chevronDown"} size={14} />
      </button>
      {open && (
        <div id={bodyId} className="ms-tile slope-profile__body">
          <ProfileGraph line={profile.along} startLabel="Approach" endLabel="Departure" className="slope-profile--along" view="side" />
          <ProfileGraph line={profile.across} startLabel="Left" endLabel="Right" className="slope-profile--across" view="rear" />
        </div>
      )}
    </div>
  );
};

export default SlopeProfile;
