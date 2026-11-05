import React, { useEffect, useId, useMemo, useRef, useState } from "react";
import Icon from "../feature/ui/Icon";
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
 *
 * With `onShare`, each row has a share icon at its end (for a pointer) and the field a
 * share button beside GO (docs/GRID_SHARING.md): the row's place, or the place in the
 * field (the row the arrow keys or the pointer are on, else the one place the text
 * reads as; never a guess between readings).
 */

// Where the placeholder's width cannot be measured (no canvas: a test), the width taken to hold the example.
const WIDE_PLACEHOLDER_PX = 480;

let measurer = null;
// The width `text` takes in the font `input` draws its placeholder in (its own where that cannot be read), or
// null where it cannot be measured.
const textWidth = (text, input) => {
  try {
    measurer = measurer ?? document.createElement("canvas").getContext("2d");
    if (!measurer) return null;
    measurer.font = getComputedStyle(input, "::placeholder").font || getComputedStyle(input).font;
    return measurer.measureText(text).width;
  } catch {
    return null;
  }
};
const TargetField = ({
  value,
  onChange,
  onChoose,
  near = null,
  placeholder = "Search by grid or lat/long",
  // Shown instead where the field is wide enough to hold it.
  widePlaceholder = "Search by grid or lat/long, e.g. 16S GC 28864 55349 or 34.54, -84.12",
  onShare = null,
  autoFocus = false,
  rowClassName = "ff-input-group",
  inputClassName = "ff-input",
  buttonClassName = "ff-btn primary",
  inputProps = {},
  // Drawn in the row before the input (a search icon).
  leading = null,
}) => {
  const id = useId();
  const inputRef = useRef(null);
  const listId = `${id}-places`;
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  // Bumped by GO when it cannot choose, so the status says its sentence again.
  const [asked, setAsked] = useState(0);
  const [wide, setWide] = useState(false);

  const shareRef = useRef(null);

  // The example is shown only where all of it fits (a cut-off coordinate would mislead): watch the field's
  // own width, which a top bar shares with its neighbours, against the example's width in the field's font.
  useEffect(() => {
    const input = inputRef.current;
    if (!input || !widePlaceholder || typeof ResizeObserver === "undefined") return undefined;
    const observer = new ResizeObserver(([entry]) => {
      const needed = textWidth(widePlaceholder, input) ?? WIDE_PLACEHOLDER_PX;
      setWide(entry.contentRect.width >= needed + 4);
    });
    observer.observe(input);
    return () => observer.disconnect();
  }, [widePlaceholder]);

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

  const choose = (choice, { byPointer = false } = {}) => {
    setOpen(false);
    setActive(-1);
    // A tap puts the phone's keyboard away, so the map it goes to is not under it; a key leaves the focus where it was.
    if (byPointer) inputRef.current?.blur();
    onChoose(choice);
  };

  // What the field's own share button sends: the row the arrow keys or the pointer are on, else the one place.
  // The row stays picked while the focus goes from the field to that button, so a key reaches either reading.
  const sharable = active >= 0 && rows[active] ? rows[active].choice
    : reading.choices.length === 1 ? reading.choices[0] : null;
  const share = (choice, { byPointer = false } = {}) => {
    if (!choice) return;
    setOpen(false);
    setActive(-1);
    // As choosing does: a tap puts the field (and a phone's keyboard) away, so closing the dialog, which gives
    // the focus back to what had it, does not open the list again over the map.
    if (byPointer) inputRef.current?.blur();
    onShare(choice);
  };

  const go = ({ byPointer = false } = {}) => {
    if (reading.choices.length === 1) {
      choose(reading.choices[0], { byPointer });
      return;
    }
    // Readings to choose from, or a sentence: show them, and say it again. The focus goes back to the
    // field, so the list closes as it always does (Escape, leaving the field) and the arrow keys reach it.
    inputRef.current?.focus();
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
        {leading}
        <input
          {...inputProps}
          ref={inputRef}
          className={inputClassName}
          placeholder={wide && widePlaceholder ? widePlaceholder : placeholder}
          autoFocus={autoFocus}
          autoComplete="off"
          spellCheck={false}
          value={value}
          role="combobox"
          aria-label={inputProps["aria-label"] ?? "Search by grid or lat/long"}
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
          onBlur={(event) => {
            // To the field's own share button: the row picked is what it shares, so it stays picked.
            if (shareRef.current && event.relatedTarget === shareRef.current) return;
            setOpen(false);
            setActive(-1);
          }}
          onKeyDown={onKeyDown}
        />
        {onShare && (
          <button
            ref={shareRef}
            type="button"
            className="target-field__share"
            onMouseDown={(event) => event.preventDefault()}
            onClick={(event) => share(sharable, { byPointer: event.detail > 0 })}
            onBlur={(event) => {
              if (event.relatedTarget === inputRef.current) return;
              setOpen(false);
              setActive(-1);
            }}
            disabled={!sharable}
            aria-label={sharable ? `Share grid ${sharable.mgrs}` : "Share grid"}
            title={sharable ? "Share grid" : "Type a grid or lat/long to share it"}
          >
            <Icon name="share" size={16} />
          </button>
        )}
        <button
          type="button"
          // Keeps the focus in the field while it is pressed (Safari and Firefox on a Mac give a clicked button none), so a field that
          // folds as it loses the focus is still there when the click lands; a click that chooses then puts the field away, as a row's does.
          onMouseDown={(event) => event.preventDefault()}
          onClick={(event) => go({ byPointer: event.detail > 0 })}
          className={buttonClassName}
        >
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
              onClick={() => choose(row.choice, { byPointer: true })}
            >
              <span className="target-field__text">
                <span className="target-field__main">{row.main}</span>
                <span className="target-field__note">{row.note}</span>
              </span>
              {onShare && (
                // For a pointer only: an option holds no control of its own (a screen reader would fold it into the
                // option's name), and the keyboard shares the row picked with the field's own share button.
                <span
                  aria-hidden="true"
                  className="target-field__row-share"
                  data-share={row.choice.mgrs}
                  title="Share grid"
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={(event) => {
                    event.stopPropagation();
                    share(row.choice, { byPointer: true });
                  }}
                >
                  <Icon name="share" size={16} />
                </span>
              )}
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
