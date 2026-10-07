import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import "./ui.css";

/*
 * A menu that opens from a button (Import, the ⋯ on a card, the account, the workspace switcher). It
 * sits under its button, kept inside the window; Esc or a click elsewhere closes it and focus goes back
 * to the button; the arrow keys move between its items. `useMenu` gives the button what it needs.
 */

export const useMenu = () => {
  const [open, setOpen] = useState(false);
  const anchorRef = useRef(null);
  const close = useCallback(() => setOpen(false), []);
  const toggle = useCallback(() => setOpen((v) => !v), []);
  return {
    open,
    close,
    toggle,
    anchorRef,
    buttonProps: { ref: anchorRef, "aria-haspopup": "menu", "aria-expanded": open, onClick: toggle },
  };
};

const ITEMS = '[role="menuitem"]:not([disabled]), [role="menuitemradio"]:not([disabled])';

const Menu = ({ open, onClose, anchorRef, label, align = "start", width = 240, offset = 6, children, className = "" }) => {
  const panel = useRef(null);
  const [place, setPlace] = useState({ left: -9999, top: -9999 });

  useLayoutEffect(() => {
    if (!open || !anchorRef.current) return;
    const anchor = anchorRef.current.getBoundingClientRect();
    const height = panel.current?.offsetHeight ?? 0;
    const room = window.innerWidth - 8;
    let left = align === "end" ? anchor.right - width : anchor.left;
    left = Math.max(8, Math.min(left, room - width));
    let top = anchor.bottom + offset;
    if (top + height > window.innerHeight - 8 && anchor.top - offset - height > 8) top = anchor.top - offset - height;
    setPlace({ left, top });
  }, [open, anchorRef, align, width, offset]);

  useEffect(() => {
    if (!open) return undefined;
    panel.current?.querySelector(ITEMS)?.focus();
    const away = (event) => {
      if (panel.current?.contains(event.target) || anchorRef.current?.contains(event.target)) return;
      onClose();
    };
    document.addEventListener("mousedown", away);
    return () => document.removeEventListener("mousedown", away);
  }, [open, onClose, anchorRef]);

  if (!open) return null;

  const onKeyDown = (event) => {
    if (event.key === "Escape") {
      event.stopPropagation();
      onClose();
      anchorRef.current?.focus();
      return;
    }
    if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
    event.preventDefault();
    const items = [...panel.current.querySelectorAll(ITEMS)];
    const at = items.indexOf(document.activeElement);
    const next = event.key === "ArrowDown" ? (at + 1) % items.length : (at - 1 + items.length) % items.length;
    items[next]?.focus();
  };

  return createPortal(
    <div
      ref={panel}
      role="menu"
      aria-label={label}
      className={`ui-menu ${className}`.trim()}
      style={{ left: place.left, top: place.top, width }}
      onKeyDown={onKeyDown}
    >
      {children}
    </div>,
    document.body,
  );
};

export default Menu;

/** One item of a menu: closes the menu, then does its thing. */
export const MenuItem = ({ icon, title, text, onSelect, onClose, danger, disabled, children, rich }) => (
  <button
    type="button"
    role="menuitem"
    disabled={disabled}
    className={`ui-menu__item${rich ? " ui-menu__item--rich" : ""}${danger ? " ui-menu__item--danger" : ""}`}
    onClick={() => {
      onClose?.();
      onSelect?.();
    }}
  >
    {icon && (rich ? <span className="ui-menu__item-icon">{icon}</span> : icon)}
    {rich ? (
      <span style={{ display: "block", minWidth: 0 }}>
        <span className="ui-menu__item-title">{title}</span>
        {text && <span className="ui-menu__item-text">{text}</span>}
      </span>
    ) : (
      <span style={{ flex: 1, minWidth: 0 }}>{title ?? children}</span>
    )}
  </button>
);
