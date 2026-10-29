import React from "react";
import Icon from "./Icon";

/*
 * Something is under way: a small ring that turns, drawn in the current text colour. Decorative inside a button whose words
 * already say what is happening; give `label` where it stands alone, and it is announced as a status.
 */
export const Spinner = ({ size = 14, label, className = "" }) => (
  <span
    className={`ui-spinner ${className}`.trim()}
    style={{ width: size, height: size }}
    role={label ? "status" : undefined}
    aria-label={label}
    aria-hidden={label ? undefined : "true"}
  />
);

/**
 * A button that asks the server for something slow (a measurement, a PDF, an upload): while `busy` it keeps its colour and
 * width, cannot be pressed again, says it is busy to a screen reader, and turns a spinner where its icon was (before its
 * words when it has none), so a click that takes a few seconds is plainly working. Everything else is the button's own.
 */
export const BusyButton = ({ busy = false, icon, iconSize = 14, className = "ui-btn", disabled, children, ...rest }) => (
  <button
    type="button"
    {...rest}
    className={`${className}${busy ? " ui-btn--busy" : ""}`}
    disabled={Boolean(disabled) || Boolean(busy)}
    aria-busy={busy ? "true" : undefined}
  >
    {busy ? <Spinner size={iconSize} /> : icon ? <Icon name={icon} size={iconSize} /> : null}
    {children}
  </button>
);

/**
 * Progress through work the server counts (a book's pages drawn): a bar filled to `done` of `total`, or one that sweeps while
 * nothing is counted yet. Named by `label`, with the count in words, so a screen reader can say how far it has got.
 */
export const ProgressBar = ({ done = 0, total = 0, label }) => {
  const counted = total > 0 && done > 0;
  const percent = counted ? Math.max(0, Math.min(100, Math.round((done / total) * 100))) : 0;
  return (
    <div
      className={`ui-progress${counted ? "" : " ui-progress--sweep"}`}
      role="progressbar"
      aria-label={label}
      aria-valuemin={counted ? 0 : undefined}
      aria-valuemax={counted ? total : undefined}
      aria-valuenow={counted ? done : undefined}
      aria-valuetext={counted ? `${done} of ${total}` : undefined}
    >
      <div className="ui-progress__fill" style={counted ? { width: `${percent}%` } : undefined} />
    </div>
  );
};

export default Spinner;
