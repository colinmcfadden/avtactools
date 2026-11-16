/*
 * A doghouse's box as markup: the roof with its label and the four rows (heading, time, distance, airspeed). The LZ/PZ's
 * own doghouses (Doghouse.jsx), whose fields can be typed in, and a route's, one a leg, worked out from its plan
 * (feature/routeDoghouses), are drawn from the same box. Its text is escaped here: a route point's name is anyone's in a
 * Mission Pack.
 */
const escapeHtml = (value) =>
  String(value ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

/**
 * The box, `id` in its roof. `editable` gives each field the `dh-input` class and `data-type` an LZ/PZ's doghouse types in,
 * and `cursor` is the fields' cursor; `className` and `style` go on the outer element, `roofStyle` on the label in the roof.
 */
export const doghouseBoxHtml = ({ id, heading, minutes, seconds, distance, airspeed }, { editable = false, cursor = "default", className = "doghouse-wrapper", style = "", roofStyle = "" } = {}) => {
  const field = (type, value, extra) =>
    `<span${editable ? ` class="dh-input" data-type="${type}"` : ""} style="cursor: ${cursor}; ${extra}">${escapeHtml(value)}</span>`;
  return `<div class="${className}" style="${style}">
            <div style="width: 0; height: 0; border-left: 30px solid transparent; border-right: 30px solid transparent; border-bottom: 20px solid black; position: relative;">
                <div${editable ? ` class="dh-input" data-type="id"` : ""} style="position: absolute; top: 2px; left: -30px; width: 60px; text-align: center; font-weight: bold; font-size: 10px; color: white; cursor: ${cursor}; ${roofStyle}">${escapeHtml(id)}</div>
            </div>
            <div style="background: white; border: 2px solid black; width: 60px; display: flex; flex-direction: column; font-family: monospace; font-weight: bold; font-size: 12px; color: black;">
                <div style="border-bottom: 1px solid black; display: flex; justify-content: center; align-items: center;">
                    ${field("heading", heading, "min-width: 20px; text-align: right; padding: 2px 0;")}
                    <span style="pointer-events: none;">°</span>
                </div>
                <div style="border-bottom: 1px solid black; display: flex; justify-content: center; align-items: center;">
                    ${field("time-m", minutes, "min-width: 15px; text-align: right; padding: 2px 0;")}
                    <span style="pointer-events: none;">+</span>
                    ${field("time-s", seconds, "min-width: 15px; text-align: left; padding: 2px 0;")}
                </div>
                <div style="border-bottom: 1px solid black; display: flex; justify-content: center; align-items: center;">
                    ${field("dist", distance, "min-width: 20px; text-align: right; padding: 2px 0;")}
                    <span style="font-size: 10px; margin-left: 1px; pointer-events: none;"> km</span>
                </div>
                <div style="display: flex; justify-content: center; align-items: center;">
                    ${field("airspeed", airspeed, "min-width: 20px; text-align: right; padding: 2px 0;")}
                    <span style="font-size: 10px; margin-left: 1px; pointer-events: none;"> kts</span>
                </div>
            </div>
        </div>`;
};
