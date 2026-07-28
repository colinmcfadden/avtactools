import React, { useState } from "react";
import Draggable from "react-draggable";
import { aircraftPreviewSvg, ICON_KEYS } from "./aircraftIcons";
import { centerSpacingM } from "./aircraftProfiles";
import "../export/ExportModal.css";
import "./aircraft.css";

const num = (value, fallback = 0) => {
  const parsed = parseFloat(value);
  return Number.isFinite(parsed) ? parsed : fallback;
};

const BLANK = {
  name: "",
  designation: "",
  icon_key: "generic",
  rotor_diameter_m: 16.36,
  rotor_tip_clearance_m: 60,
  default_airspeed_kts: 100,
  default_airspeed_type: "ground",
  max_indicated_kts: 160,
  default_altitude_ft: 50,
  default_altitude_ref: "agl",
  default_fuel_flow_lb_hr: 960,
  default_gross_weight_lb: 16000,
};

const errorText = (err, fallback) =>
  err?.response?.data?.error || err?.message || fallback;

/**
 * Lets a user build aircraft the admin-managed master list doesn't cover.
 * Master profiles are shown read-only for reference; only the user's own
 * profiles are editable here.
 */
const AircraftProfileModal = ({
  masterProfiles,
  customProfiles,
  onCreate,
  onUpdate,
  onDelete,
  onClose,
}) => {
  const nodeRef = React.useRef(null);
  const [draft, setDraft] = useState(null); // null = list view
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const set = (patch) => setDraft((d) => ({ ...d, ...patch }));

  const startNew = () => {
    setError("");
    setDraft({ ...BLANK });
  };

  const startCopy = (source) => {
    setError("");
    setDraft({
      ...BLANK,
      ...source,
      id: undefined,
      name: `${source.name} (copy)`,
      designation: source.designation,
    });
  };

  const save = async () => {
    if (!draft.name.trim() || !draft.designation.trim()) {
      setError("Name and designation are required.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      const payload = {
        name: draft.name.trim(),
        designation: draft.designation.trim(),
        icon_key: draft.icon_key,
        rotor_diameter_m: num(draft.rotor_diameter_m, 16.36),
        rotor_tip_clearance_m: num(draft.rotor_tip_clearance_m, 60),
        default_airspeed_kts: num(draft.default_airspeed_kts, 100),
        default_airspeed_type: draft.default_airspeed_type,
        max_indicated_kts: num(draft.max_indicated_kts, 160),
        default_altitude_ft: num(draft.default_altitude_ft, 50),
        default_altitude_ref: draft.default_altitude_ref,
        default_fuel_flow_lb_hr: num(draft.default_fuel_flow_lb_hr, 960),
        default_gross_weight_lb: num(draft.default_gross_weight_lb, 16000),
      };
      if (draft.id) await onUpdate(draft.id, payload);
      else await onCreate(payload);
      setDraft(null);
    } catch (err) {
      setError(errorText(err, "Couldn't save that profile."));
    } finally {
      setBusy(false);
    }
  };

  const remove = async (profile) => {
    if (!window.confirm(`Delete "${profile.name}"?`)) return;
    setBusy(true);
    setError("");
    try {
      await onDelete(profile.id);
    } catch (err) {
      setError(errorText(err, "Couldn't delete that profile."));
    } finally {
      setBusy(false);
    }
  };

  const field = (label, key, props = {}) => (
    <div className="input-group">
      <label>{label}</label>
      <input
        value={draft[key] ?? ""}
        onChange={(event) => set({ [key]: event.target.value })}
        {...props}
      />
    </div>
  );

  return (
    <Draggable nodeRef={nodeRef} handle=".modal-header">
      <div
        ref={nodeRef}
        className="export-modal-container glass-panel"
        style={{ width: "min(560px, 95vw)", maxHeight: "85vh", overflowY: "auto" }}
      >
        <div className="modal-header">
          <h3>{draft ? (draft.id ? "Edit aircraft" : "New aircraft") : "Aircraft profiles"}</h3>
          <button className="close-btn" onClick={onClose} aria-label="Close">
            ×
          </button>
        </div>

        <div className="modal-body">
          {error && <div className="ac-error" role="alert">{error}</div>}

          {!draft && (
            <>
              <p className="ac-hint">
                Build an aircraft the master list doesn't cover. Your profiles are private
                to your account and appear in the aircraft picker alongside the standard ones.
              </p>

              <div className="ac-section-label">Your aircraft</div>
              {customProfiles.length === 0 ? (
                <p className="ac-empty">None yet.</p>
              ) : (
                <ul className="ac-list">
                  {customProfiles.map((p) => (
                    <li key={p.id} className="ac-list__item">
                      <span
                        className="ac-list__glyph"
                        aria-hidden="true"
                        dangerouslySetInnerHTML={{ __html: aircraftPreviewSvg(p.icon_key, 26) }}
                      />
                      <span className="ac-list__text">
                        <strong>{p.designation}</strong>
                        <small>
                          {p.name} · {centerSpacingM(p).toFixed(0)} m spacing ·{" "}
                          {Math.round(p.default_airspeed_kts)} kt
                        </small>
                      </span>
                      <span className="ac-list__actions">
                        <button type="button" onClick={() => { setError(""); setDraft({ ...p }); }}>
                          Edit
                        </button>
                        <button type="button" className="ac-danger" onClick={() => remove(p)} disabled={busy}>
                          Delete
                        </button>
                      </span>
                    </li>
                  ))}
                </ul>
              )}

              <div className="ac-section-label">Standard aircraft</div>
              <p className="ac-hint ac-hint--tight">
                Managed centrally — copy one as a starting point for your own.
              </p>
              <ul className="ac-list">
                {masterProfiles.map((p) => (
                  <li key={p.slug} className="ac-list__item">
                    <span
                      className="ac-list__glyph"
                      aria-hidden="true"
                      dangerouslySetInnerHTML={{ __html: aircraftPreviewSvg(p.icon_key, 26) }}
                    />
                    <span className="ac-list__text">
                      <strong>{p.designation}</strong>
                      <small>
                        {centerSpacingM(p).toFixed(0)} m spacing ·{" "}
                        {Math.round(p.default_airspeed_kts)} kt
                        {p.perf_source === "published" && " · unverified perf"}
                      </small>
                    </span>
                    <span className="ac-list__actions">
                      <button type="button" onClick={() => startCopy(p)}>
                        Copy
                      </button>
                    </span>
                  </li>
                ))}
              </ul>
            </>
          )}

          {draft && (
            <>
              <div className="two-col-grid">
                <div className="input-group">
                  <label>Designation</label>
                  <input
                    value={draft.designation}
                    onChange={(event) => set({ designation: event.target.value })}
                    placeholder="MH-47G"
                    maxLength={40}
                    autoFocus
                  />
                </div>
                <div className="input-group">
                  <label>Name</label>
                  <input
                    value={draft.name}
                    onChange={(event) => set({ name: event.target.value })}
                    placeholder="MH-47G Chinook"
                    maxLength={120}
                  />
                </div>
              </div>

              <div className="input-group">
                <label>Map icon</label>
                <div className="ac-icon-choices">
                  {ICON_KEYS.map((key) => (
                    <button
                      key={key}
                      type="button"
                      className={`ac-icon-choice${draft.icon_key === key ? " on" : ""}`}
                      onClick={() => set({ icon_key: key })}
                      title={key}
                      dangerouslySetInnerHTML={{ __html: aircraftPreviewSvg(key, 30) }}
                    />
                  ))}
                </div>
              </div>

              <div className="form-divider">Footprint &amp; separation</div>
              <div className="two-col-grid">
                {field("Rotor diameter (m)", "rotor_diameter_m", {
                  type: "number", step: "0.01", min: "1", max: "60",
                })}
                {field("Tip clearance (m)", "rotor_tip_clearance_m", {
                  type: "number", step: "1", min: "0", max: "1000",
                })}
              </div>
              <p className="ac-hint ac-hint--tight">
                Center spacing works out to{" "}
                <strong>
                  {(num(draft.rotor_diameter_m) + num(draft.rotor_tip_clearance_m)).toFixed(1)} m
                </strong>
                . Tandem rotors: use the overall fore-to-aft span as the diameter.
              </p>

              <div className="form-divider">Route planning defaults</div>
              <div className="two-col-grid">
                {field("Cruise airspeed (kt)", "default_airspeed_kts", { type: "number", step: "1" })}
                <div className="input-group">
                  <label>Airspeed reference</label>
                  <select
                    value={draft.default_airspeed_type}
                    onChange={(event) => set({ default_airspeed_type: event.target.value })}
                  >
                    <option value="ground">GS (Ground)</option>
                    <option value="indicated">KIAS (Indicated)</option>
                    <option value="true">KTAS (True)</option>
                  </select>
                </div>
                {field("Max indicated (kt)", "max_indicated_kts", { type: "number", step: "1" })}
                <div className="input-group">
                  <label>Altitude reference</label>
                  <select
                    value={draft.default_altitude_ref}
                    onChange={(event) => set({ default_altitude_ref: event.target.value })}
                  >
                    <option value="agl">AGL</option>
                    <option value="msl">MSL</option>
                  </select>
                </div>
                {field("Default altitude (ft)", "default_altitude_ft", { type: "number", step: "10" })}
                {field("Fuel flow (lb/hr)", "default_fuel_flow_lb_hr", { type: "number", step: "10" })}
                {field("Gross weight (lb)", "default_gross_weight_lb", { type: "number", step: "100" })}
              </div>

              <p className="ac-hint">
                Exports built on this profile still open in AMPS as a UH-60L — an airframe
                can only come from files AMPS produced, which an administrator attaches to a
                standard profile.
              </p>
            </>
          )}
        </div>

        <div className="modal-footer" style={{ display: "flex", gap: "8px", justifyContent: "flex-end" }}>
          {draft ? (
            <>
              <button className="cancel-btn" onClick={() => setDraft(null)} disabled={busy}>
                Back
              </button>
              <button className="export-btn" onClick={save} disabled={busy}>
                {busy ? "Saving…" : draft.id ? "Save changes" : "Create aircraft"}
              </button>
            </>
          ) : (
            <>
              <button className="cancel-btn" onClick={onClose}>
                Close
              </button>
              <button className="export-btn" onClick={startNew}>
                + New aircraft
              </button>
            </>
          )}
        </div>
      </div>
    </Draggable>
  );
};

export default AircraftProfileModal;
