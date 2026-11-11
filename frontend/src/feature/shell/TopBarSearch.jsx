import React, { useCallback, useEffect, useRef, useState } from "react";
import TargetField from "../../components/TargetField";
import { formatMGRS } from "../../utils/Helpers";
import Icon from "../ui/Icon";

/*
 * The top bar's search (TargetField), which opens as a Spotlight does: taken into the field (a click, a tab, typing),
 * it grows out of its place in the bar into a larger field over the map, with its list under it, and the map dims a
 * little behind it. It folds back into the bar when the focus leaves it, on Escape (once the list is closed), and once
 * a place is chosen or shared. The bar keeps its room, so nothing beside it moves.
 *
 * On a phone (`phone`) it is the top of the screen (PhoneBar): it opens the same way, across the whole width and over
 * the menu button beside it, and a grid is spaced as it is typed.
 */

// How wide it grows when it opens, and how far it keeps from the window's edges.
export const OPEN_WIDTH = 760;
const EDGE = 16;
// A phone has no width to spare: the open field takes all of it but this.
export const PHONE_EDGE = 8;

/**
 * Where the open field goes, as `left` (from the slot's own left) and `width`: as wide as OPEN_WIDTH or the window
 * allows (never narrower than the slot), centred on the slot, and kept `edge` inside the window.
 */
export const openBox = (slot, viewportWidth, edge = EDGE) => {
  const width = Math.max(slot.width, Math.min(OPEN_WIDTH, viewportWidth - 2 * edge));
  // A window with no room to grow: it stays where it is.
  if (width <= slot.width) return { left: 0, width: Math.round(slot.width) };
  const centre = slot.left + slot.width / 2;
  const left = Math.max(edge, Math.min(centre - width / 2, viewportWidth - edge - width));
  return { left: Math.round(left - slot.left), width: Math.round(width) };
};

/**
 * What a phone's field holds for what was typed: a grid spaced as it is typed (a phone's keyboard makes the spaces a
 * chore). Text that starts with a letter is a place's name (no grid does) and is left as typed; so is a local point's,
 * which can also start with a dot (.CP1).
 */
export const spacedAsTyped = (text, { places = false, localPoints = false } = {}) => {
  const name = localPoints ? /^\s*[.\p{L}]/u : places ? /^\s*\p{L}/u : null;
  return name?.test(text) ? text : formatMGRS(text);
};

const TopBarSearch = ({ value, onChange, onChoose, onShare, near, searchPlaces = null, localPoints = null, onSearchStart, phone = false }) => {
  const slotRef = useRef(null);
  const [open, setOpen] = useState(false);
  const [box, setBox] = useState(null);

  const measure = useCallback(() => {
    const slot = slotRef.current?.getBoundingClientRect();
    if (slot) setBox(openBox(slot, window.innerWidth, phone ? PHONE_EDGE : EDGE));
  }, [phone]);

  // Measured as it opens, in the same render, so it grows from where it is to where it goes.
  const expand = useCallback(() => {
    measure();
    setOpen(true);
  }, [measure]);

  useEffect(() => {
    if (!open) return undefined;
    window.addEventListener("resize", measure);
    return () => window.removeEventListener("resize", measure);
  }, [open, measure]);

  return (
    <div className="topbar-search-slot" ref={slotRef}>
      <div
        className={`topbar-spotlight${open ? " topbar-spotlight--open" : ""}`}
        style={open && box ? { left: box.left, width: box.width } : undefined}
        onFocus={(event) => {
          // The field itself, not its buttons: the focus that comes back to the share button as its dialog closes
          // leaves the bar as it is.
          if (event.target.tagName === "INPUT") expand();
        }}
        onPointerDown={(event) => {
          // A click on the field folded by a choice or Escape, which still has the focus (so no focus event comes). Any other press on it opens
          // it by its focus, which a touch gives only once it ends (a press dragged away or cancelled gives none, and opens nothing).
          if (!open && event.target.tagName === "INPUT" && document.activeElement === event.target) expand();
        }}
        onMouseDown={(event) => {
          // A press on the field's own surface (its magnifier, its padding, a disabled button, the list's sentence) takes the focus nowhere,
          // which would fold it: it goes to the text instead, and opens a folded field.
          if (event.target.tagName === "INPUT" || event.target.closest("button:not(:disabled)")) return;
          event.preventDefault();
          event.currentTarget.querySelector("input")?.focus();
        }}
        onBlur={(event) => {
          if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false);
        }}
        onKeyDown={(event) => {
          // TargetField takes the first Escape to close its list; the next folds the field and leaves the focus in it.
          if (event.key === "Escape" && open) {
            event.preventDefault();
            setOpen(false);
          }
        }}
      >
        <TargetField
          value={value}
          onChange={(next) => {
            onChange(phone ? spacedAsTyped(next, { places: Boolean(searchPlaces), localPoints: Boolean(localPoints?.length) }) : next);
            // Typing again in a field folded by Escape or a choice opens it again.
            if (!open) expand();
          }}
          onChoose={(choice) => {
            setOpen(false);
            onChoose(choice);
          }}
          onShare={(choice) => {
            setOpen(false);
            onShare(choice);
          }}
          near={near}
          searchPlaces={searchPlaces}
          localPoints={localPoints}
          inputProps={
            phone
              ? {
                  type: "text",
                  // What is there is typed over: the last search's grid is rarely the start of the next.
                  onFocus: (event) => {
                    event.target.select();
                    onSearchStart?.();
                  },
                }
              : { onFocus: onSearchStart }
          }
          terse={phone}
          leading={<Icon name="search" size={phone ? 18 : 16} className="topbar-search__lead" />}
          rowClassName="topbar-search"
          inputClassName="topbar-search__input"
          buttonClassName="topbar-search__go"
        />
      </div>
      <div className={`topbar-spotlight-scrim${open ? " topbar-spotlight-scrim--shown" : ""}`} aria-hidden="true" />
    </div>
  );
};

export default TopBarSearch;
