import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import Icon from "../ui/Icon";
import "./shell.css";

/*
 * The dock on the right of the map: one panel at a time (LZ/PZ, Routes, Threats, Imports, and Pack in
 * a Mission Pack) and a rail of icons that picks it. Clicking the panel's own icon folds the panel away,
 * leaving the rail. An icon carries a count, or a dot for a state: amber, unsaved changes here; violet,
 * changed in the pack since this person looked.
 *
 * Below 1100 px wide the dock is a bottom sheet (shell.css), as in the Android app: the rail is a row of
 * tabs along its top, and it rests at a peek, half the window or nearly all of it. Dragging its handle
 * moves between them; picking a tab opens it to half if it was at the peek. The map ends where the sheet
 * begins, so a target set by grid lands in view.
 */

const NARROW = "(max-width: 1100px)";
const PEEK = 132;

const useNarrow = () => {
  const [narrow, setNarrow] = useState(() => typeof window !== "undefined" && window.matchMedia?.(NARROW).matches);
  useEffect(() => {
    const query = window.matchMedia?.(NARROW);
    if (!query) return undefined;
    const change = () => setNarrow(query.matches);
    query.addEventListener?.("change", change);
    return () => query.removeEventListener?.("change", change);
  }, []);
  return narrow;
};

const sheetHeights = () => {
  const h = window.innerHeight;
  return { peek: PEEK, half: Math.round(h * 0.48), full: h - 56 };
};

// How long the sheet takes to settle at a new height: its transition in shell.css.
const SETTLE_MS = 200;

/**
 * Ends the map where the sheet begins, so a target set by grid lands in view and the map-style
 * switcher shows. The map is the dock's sibling and cannot read a variable set on the dock, so the
 * sheet's height (`--sheet-height`) and the map's bottom (`--map-bottom`) go on their shared parent,
 * the map area, where shell.css reads both. `height` is null outside the bottom-sheet layout.
 *
 * The map's box changes only while the sheet is still: dragged, the sheet slides over a map that
 * reaches down to the peek; eased up, over the map's old bottom, which follows once it arrives. No gap
 * opens between them, and Leaflet re-fits once per move, not every frame (each re-fit re-renders the
 * app through the map's moveend).
 */
const useMapAboveSheet = (asideRef, height, dragging) => {
  const mapBottom = useRef(null);
  useLayoutEffect(() => {
    const frame = asideRef.current?.parentElement;
    if (!frame || height === null) return undefined;
    const setMapBottom = (px) => {
      mapBottom.current = px;
      frame.style.setProperty("--map-bottom", `${px}px`);
    };
    frame.style.setProperty("--sheet-height", `${height}px`);
    if (dragging) setMapBottom(PEEK);
    else if (mapBottom.current === null || height <= mapBottom.current) setMapBottom(height);
    else {
      const settled = setTimeout(() => setMapBottom(height), SETTLE_MS);
      return () => clearTimeout(settled);
    }
    return undefined;
  }, [asideRef, height, dragging]);

  // Out of the bottom-sheet layout the map sits beside the dock again (shell.css's wide rules).
  const sheetLayout = height !== null;
  useLayoutEffect(() => {
    if (!sheetLayout) return undefined;
    const frame = asideRef.current?.parentElement;
    return () => {
      mapBottom.current = null;
      frame?.style.removeProperty("--sheet-height");
      frame?.style.removeProperty("--map-bottom");
    };
  }, [asideRef, sheetLayout]);
};

/** Which panel shows, and whether it is folded away. */
export const useDock = (initial = "lz") => {
  const [panel, setPanel] = useState(initial);
  const [collapsed, setCollapsed] = useState(false);
  const pick = useCallback(
    (next) => {
      if (next === panel) setCollapsed((c) => !c);
      else {
        setPanel(next);
        setCollapsed(false);
      }
    },
    [panel],
  );
  const show = useCallback((next) => {
    setPanel(next);
    setCollapsed(false);
  }, []);
  return { panel, collapsed, pick, show, setCollapsed };
};

export const DockRail = ({ items, active, collapsed, onPick }) => (
  <nav className="shell-dock__rail" aria-label="Panels">
    {items.filter((item) => !item.hidden).map((item) => (
      <button
        key={item.key}
        type="button"
        aria-label={item.label}
        aria-pressed={item.key === active && !collapsed}
        className={`shell-rail__item${item.key === active && !collapsed ? " shell-rail__item--active" : ""}${item.key === "pack" ? " shell-rail__item--pack" : ""}`}
        onClick={() => onPick(item.key)}
      >
        <Icon name={item.icon} size={20} strokeWidth={1.7} color={item.key === active && !collapsed && item.iconColor ? item.iconColor : undefined} />
        <span className="shell-rail__label">{item.label}</span>
        {item.count > 0 && <span className="shell-rail__count">{item.count > 99 ? "99+" : item.count}</span>}
        {!(item.count > 0) && item.dot && (
          <span className={`shell-rail__dot${item.dot === "pack" ? " shell-rail__dot--pack" : ""}`} title={item.dotLabel} />
        )}
      </button>
    ))}
  </nav>
);

const Dock = ({ items, dock, children }) => {
  const narrow = useNarrow();
  // A phone starts with the sheet lowered, so the map is usable; a tablet has room for half.
  const [sheet, setSheet] = useState(() => (typeof window !== "undefined" && window.innerWidth < 700 ? "peek" : "half"));
  const drag = useRef(null);
  const [dragHeight, setDragHeight] = useState(null);
  const asideRef = useRef(null);

  // The sheet's heights follow the window's (a tablet turned on its side), and the map's bottom with them.
  const [, setResized] = useState(0);
  useEffect(() => {
    if (!narrow) return undefined;
    const onResize = () => setResized((n) => n + 1);
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, [narrow]);

  const sheetHeight = narrow ? dragHeight ?? sheetHeights()[sheet] : null;
  const dragging = dragHeight !== null;
  useMapAboveSheet(asideRef, sheetHeight, dragging);

  const pick = (key) => {
    if (narrow) {
      if (key === dock.panel && sheet !== "peek") {
        setSheet("peek");
        return;
      }
      dock.show(key);
      if (sheet === "peek") setSheet("half");
      return;
    }
    dock.pick(key);
  };

  const onPointerDown = (event) => {
    drag.current = { y: event.clientY, start: sheetHeights()[sheet] };
    event.currentTarget.setPointerCapture?.(event.pointerId);
  };
  const onPointerMove = (event) => {
    if (!drag.current) return;
    const { peek, full } = sheetHeights();
    setDragHeight(Math.max(peek, Math.min(full, drag.current.start + (drag.current.y - event.clientY))));
  };
  const onPointerUp = () => {
    if (!drag.current) return;
    const heights = sheetHeights();
    const at = dragHeight ?? drag.current.start;
    const nearest = Object.entries(heights).sort((a, b) => Math.abs(a[1] - at) - Math.abs(b[1] - at))[0][0];
    // A tap on the handle (no drag) steps up, and from the top back to the peek.
    if (dragHeight === null) setSheet(sheet === "peek" ? "half" : sheet === "half" ? "full" : "peek");
    else setSheet(nearest);
    drag.current = null;
    setDragHeight(null);
  };

  const collapsed = !narrow && dock.collapsed;
  return (
    <aside
      ref={asideRef}
      className={`shell-dock${collapsed ? " shell-dock--collapsed" : ""}`}
      style={dragging ? { transition: "none" } : undefined}
      aria-label="Panels"
    >
      {narrow && (
        <button
          type="button"
          className="shell-sheet__handle"
          aria-label={sheet === "full" ? "Lower the panel" : "Raise the panel"}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          onPointerCancel={onPointerUp}
        />
      )}
      {narrow && <DockRail items={items} active={dock.panel} collapsed={false} onPick={pick} />}
      <div className="shell-dock__panel" id="dock-panel">{children}</div>
      {!narrow && <DockRail items={items} active={dock.panel} collapsed={dock.collapsed} onPick={pick} />}
    </aside>
  );
};

export default Dock;

/** The header of a panel: its title, a line under it, and the button that folds it away. */
export const PanelHead = ({ title, subtitle, onCollapse, children }) => (
  <div className="shell-dock__head">
    <div style={{ flex: 1, minWidth: 0 }}>
      <div className="shell-dock__title">{title}{children}</div>
      {subtitle && <div className="shell-dock__subtitle">{subtitle}</div>}
    </div>
    {onCollapse && (
      <button type="button" className="ui-btn ui-btn--ghost ui-btn--30 ui-btn--square shell-dock__collapse" aria-label="Collapse panel" onClick={onCollapse}>
        <Icon name="chevronRight" size={15} />
      </button>
    )}
  </div>
);

/** A labelled group in a panel, with its count and an action on the right. */
export const PanelSection = ({ label, count, action, children }) => (
  <>
    <div className="shell-section">
      <div className="ui-label">
        {label}
        {count !== undefined && <span className="ui-label__count">{count}</span>}
      </div>
      {action}
    </div>
    {children}
  </>
);
