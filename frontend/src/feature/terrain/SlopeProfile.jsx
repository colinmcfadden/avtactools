import React, { useId, useState } from "react";
import Icon from "../ui/Icon";
import "./slopeProfile.css";

// The graph's own units: it is drawn in a 300 by 70 box and stretched to the tile's width (so its words are HTML, which is not stretched).
const W = 300;
const H = 70;
const TOP = 4;
const BOTTOM = 66;
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

/** One profile (profileLine's, with its words): the ground as a line over a shaded fill, the LZ as a band, its two ends named. */
export const ProfileGraph = ({ line, startLabel, endLabel, className }) => {
  const known = line.elevationsFt.filter((h) => h !== null);
  if (known.length < 2) return null;
  const s = scaleOf(line);
  const runs = runsOf(line);
  const points = (run) => run.map(([d, h]) => `${s.x(d).toFixed(1)},${s.y(h).toFixed(1)}`).join(" ");
  return (
    <figure className={`slope-profile ${className ?? ""}`}>
      <figcaption className="slope-profile__words">{line.words}</figcaption>
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
        <span className="slope-profile__axis slope-profile__axis--high" aria-hidden="true">{Math.round(s.high)}</span>
        <span className="slope-profile__axis slope-profile__axis--low" aria-hidden="true">{Math.round(s.low)}</span>
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
          <ProfileGraph line={profile.along} startLabel="Approach" endLabel="Departure" className="slope-profile--along" />
          <ProfileGraph line={profile.across} startLabel="Left" endLabel="Right" className="slope-profile--across" />
        </div>
      )}
    </div>
  );
};

export default SlopeProfile;
