import React, { useId } from "react";
import { SLOPE_OPACITY } from "./slopeOpacity";
import "./slopeOpacity.css";

/**
 * The slider under the Slope Map switch: how opaque the slope map is drawn, a percentage of the raster's own (slopeOpacity.js), its value beside it in a
 * fixed width so the track never moves under the pointer. Named "Slope map opacity", which holds the word it shows, and its value said in words: a range
 * read bare would say "55" with no unit.
 */
const SlopeOpacityControl = ({ value, onChange }) => {
  const id = useId();
  return (
    <div className="slope-opacity">
      <label className="slope-opacity__label" htmlFor={id}>Opacity</label>
      <input
        id={id}
        className="slope-opacity__range"
        type="range"
        min={SLOPE_OPACITY.MIN}
        max={SLOPE_OPACITY.MAX}
        step={SLOPE_OPACITY.STEP}
        value={value}
        aria-label="Slope map opacity"
        aria-valuetext={`${value} percent`}
        onChange={(e) => onChange(Number(e.target.value))}
      />
      <output className="slope-opacity__value" htmlFor={id} aria-hidden="true">{value}%</output>
    </div>
  );
};

export default SlopeOpacityControl;
