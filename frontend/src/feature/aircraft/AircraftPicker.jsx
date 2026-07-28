import React from "react";
import { aircraftPreviewSvg } from "./aircraftIcons";
import { FALLBACK_PROFILE, centerSpacingM } from "./aircraftProfiles";

/**
 * Mission default aircraft. Drives new map icons, separation alerts, LZ
 * capacity, and route-planning defaults. Individual aircraft already on the
 * map keep whatever profile they were placed with.
 *
 * Every input is treated as possibly missing. This renders inside the control
 * panel that hosts the whole map workspace, so a profile that hasn't loaded —
 * or a caller that forgets a prop — must degrade to the built-in UH-60L rather
 * than throw and take the app down with it.
 */
const AircraftPicker = ({
  profiles,
  activeProfile,
  onSelect,
  onManage,
  canManage = false,
  compact = false,
}) => {
  const active = activeProfile || FALLBACK_PROFILE;
  const supplied = Array.isArray(profiles) ? profiles.filter(Boolean) : [];
  // Guarantee the active aircraft is selectable even before the list arrives,
  // otherwise the <select> renders with no matching option and appears blank.
  const list = supplied.length
    ? supplied
    : [active];
  const handleSelect = (event) => {
    if (typeof onSelect === "function") onSelect(event.target.value);
  };

  if (compact) {
    return (
      <select
        className="aircraft-picker-select"
        value={active.slug || ""}
        onChange={handleSelect}
        title={`Aircraft: ${active.name || "—"}`}
        aria-label="Mission aircraft"
      >
        {list.map((p) => (
          <option key={p.slug || p.id} value={p.slug}>
            {p.designation}
          </option>
        ))}
      </select>
    );
  }

  return (
    <div className="aircraft-picker">
      <div className="aircraft-picker__row">
        <span
          className="aircraft-picker__glyph"
          aria-hidden="true"
          dangerouslySetInnerHTML={{
            __html: aircraftPreviewSvg(active.icon_key, 26),
          }}
        />
        <select
          className="aircraft-picker-select"
          value={active.slug || ""}
          onChange={handleSelect}
          aria-label="Mission aircraft"
        >
          {list.map((p) => (
            <option key={p.slug || p.id} value={p.slug}>
              {p.designation} — {p.name}
              {p.is_system ? "" : " (yours)"}
            </option>
          ))}
        </select>
      </div>

      <div className="aircraft-picker__meta">
        <span title="Center-to-center spacing: rotor diameter plus required tip clearance">
          {centerSpacingM(active).toFixed(0)} m spacing
        </span>
        <span title="Cruise airspeed used as the planning default for new route legs">
          {Math.round(active.default_airspeed_kts || 0)} kt
        </span>
        {/* Say plainly when the numbers were never validated against a real
            AMPS model — this feeds fuel and timing. */}
        {active.perf_source === "published" && (
          <span className="aircraft-picker__warn" title="Seeded from public specifications, not from an AMPS vehicle model. Verify before planning fuel.">
            unverified perf
          </span>
        )}
        {canManage && (
          <button type="button" className="aircraft-picker__manage" onClick={onManage}>
            Manage
          </button>
        )}
      </div>
    </div>
  );
};

export default AircraftPicker;
