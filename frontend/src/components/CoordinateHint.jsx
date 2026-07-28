import React from "react";
import { formatDecimal, parseCoordinate } from "../utils/coordParse";
import "./CoordinateHint.css";

/**
 * Confirms, as the user types, that what's in the target field was recognised
 * as a lat/long and will be converted to a grid.
 *
 * Silent for grids and for anything unrecognised — an empty or half-typed
 * field shouldn't nag. The point is that nobody presses GO wondering whether
 * their pasted coordinate was understood.
 */
const CoordinateHint = ({ value }) => {
  const parsed = parseCoordinate(value);
  if (!parsed) return null;

  return (
    <div className="coord-hint" role="status">
      <span className="coord-hint__tag">{parsed.label}</span>
      <span className="coord-hint__value">{formatDecimal(parsed.lat, parsed.lon)}</span>
      <span className="coord-hint__note">→ converts to MGRS</span>
    </div>
  );
};

export default CoordinateHint;
