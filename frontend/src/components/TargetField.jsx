import React, { useId, useMemo, useState } from "react";
import { nearestFirst, readTarget } from "../utils/targetReading";
import "./TargetField.css";

/**
 * The target field: a grid or a lat/long in any notation, with a list under it,
 * as it is typed, of the places the text could be (utils/targetReading.js).
 *
 * One place shows as two rows, its grid and its lat/long; an unsigned pair
 * ("34 84") as its readings, nearest the map first, under a sentence saying why
 * there is more than one; anything else as a sentence. Choosing a row (a click,
 * a tap, or the arrow keys and Enter) hands the place to `onChoose`. GO, or
 * Enter with no row picked, chooses the one place there is, and never guesses
 * between readings.
 *
 * It is an ARIA combobox: the input owns a listbox, the row picked by the
 * arrow keys is its active descendant, and a polite status says what the list
 * holds, so a screen reader hears the place without leaving the field.
 */
const TargetField = ({
  value,
  onChange,
  onChoose,
  near = null,
  placeholder = "MGRS grid or lat/long",
  autoFocus = false,
  rowClassName = "ff-input-group",
  inputClassName = "ff-input",
  buttonClassName = "ff-btn primary",
  inputProps = {},
}) => {
  const id = useId();
  const listId = `${id}-places`;
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  // Bumped by GO when it cannot choose, so the status says its sentence again.
  const [asked, setAsked] = useState(0);

  const reading = useMemo(() => readTarget(value), [value]);
  const rows = useMemo(() => {
    const choices = nearestFirst(reading.choices, near);
    if (choices.length === 1) {
      const [only] = choices;
      // A grid says its precision on its own row; a lat/long says how it was read on its.
      const grid = only.how.startsWith("MGRS");
      return [
        { key: "mgrs", choice: only, main: only.mgrs, note: grid ? only.how : "MGRS grid" },
        { key: "latlong", choice: only, main: only.latLong, note: grid ? "Lat/long of the grid square's centre" : `Lat/long, ${only.how.toLowerCase()}` },
      ];
    }
    return choices.map((choice, index) => ({ key: `reading-${index}`, choice, main: choice.latLong, note: choice.mgrs }));
  }, [reading, near]);

  const shown = open && (rows.length > 0 || Boolean(reading.message));
  const optionId = (index) => `${id}-place-${index}`;

  const choose = (choice) => {
    setOpen(false);
    setActive(-1);
    onChoose(choice);
  };

  const go = () => {
    if (reading.choices.length === 1) {
      choose(reading.choices[0]);
      return;
    }
    // Readings to choose from, or a sentence: show them, and say it again.
    setOpen(true);
    setAsked((n) => n + 1);
  };

  const onKeyDown = (event) => {
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      setOpen(true);
      if (rows.length === 0) return;
      const step = event.key === "ArrowDown" ? 1 : -1;
      setActive((current) => {
        if (current < 0) return step > 0 ? 0 : rows.length - 1;
        return (current + step + rows.length) % rows.length;
      });
      return;
    }
    if (event.key === "Enter") {
      event.preventDefault();
      if (shown && active >= 0 && rows[active]) choose(rows[active].choice);
      else go();
      return;
    }
    if (event.key === "Escape" && shown) {
      // Closes the list only; the field (and whatever holds it) stays.
      event.preventDefault();
      event.stopPropagation();
      setOpen(false);
      setActive(-1);
    }
  };

  const status = (() => {
    if (!shown) return "";
    const parts = [];
    if (reading.message) parts.push(reading.message);
    if (rows.length === 2 && reading.choices.length === 1) parts.push(`${rows[0].main}, ${rows[1].main}. Press Enter to go there.`);
    else if (rows.length > 0) parts.push(`${rows.length} places. Use the arrow keys to choose one.`);
    // A zero-width space that alternates, so the same sentence is read again after GO.
    return parts.join(" ") + (asked % 2 ? "​" : "");
  })();

  return (
    <div className="target-field">
      <div className={rowClassName}>
        <input
          {...inputProps}
          className={inputClassName}
          placeholder={placeholder}
          autoFocus={autoFocus}
          autoComplete="off"
          spellCheck={false}
          value={value}
          role="combobox"
          aria-label={inputProps["aria-label"] ?? "Target: MGRS grid or lat/long"}
          aria-autocomplete="list"
          aria-expanded={shown}
          aria-controls={listId}
          aria-activedescendant={shown && active >= 0 ? optionId(active) : undefined}
          onChange={(event) => {
            onChange(event.target.value);
            setOpen(true);
            setActive(-1);
          }}
          onFocus={(event) => {
            setOpen(true);
            inputProps.onFocus?.(event);
          }}
          onBlur={() => {
            setOpen(false);
            setActive(-1);
          }}
          onKeyDown={onKeyDown}
        />
        <button type="button" onClick={go} className={buttonClassName}>
          GO
        </button>
      </div>

      <div className={`target-field__drop ${shown ? "" : "target-field__drop--hidden"}`}>
        {shown && reading.message && (
          <p className={`target-field__message ${asked && rows.length !== 1 ? "target-field__message--asked" : ""}`}>
            {reading.message}
          </p>
        )}
        <ul id={listId} role="listbox" aria-label="Places" className="target-field__list">
          {shown && rows.map((row, index) => (
            <li
              key={row.key}
              id={optionId(index)}
              role="option"
              aria-selected={index === active}
              className={`target-field__option ${index === active ? "target-field__option--active" : ""}`}
              // Keeps the focus in the field, so choosing does not first close the list by blurring it.
              onMouseDown={(event) => event.preventDefault()}
              onMouseEnter={() => setActive(index)}
              onClick={() => choose(row.choice)}
            >
              <span className="target-field__main">{row.main}</span>
              <span className="target-field__note">{row.note}</span>
            </li>
          ))}
        </ul>
      </div>

      {/* A live region with no role, so it is not taken for the panel's own status lines. */}
      <div className="target-field__status" aria-live="polite" aria-atomic="true">
        {status}
      </div>
    </div>
  );
};

export default TargetField;
