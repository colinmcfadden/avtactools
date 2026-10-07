import React, { useEffect, useId, useRef } from "react";
import { createPortal } from "react-dom";
import Icon from "./Icon";
import "./ui.css";

/*
 * A modal dialog as the redesign draws them: a title with an icon, an optional line under it, a body,
 * and a footer with a note on the left and the buttons on the right. role="dialog" and aria-modal,
 * labelled by its title; focus moves in when it opens, stays inside while it is open (Tab and
 * Shift+Tab go round), and goes back to whatever opened it when it closes. Esc closes it, as does a
 * click on the backdrop (not a drag that only ends there) unless `dismissible` is false.
 */

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

const Dialog = ({
  title,
  subtitle,
  icon,
  iconTone,
  onClose,
  children,
  footer,
  note,
  width,
  initialFocus,
  dismissible = true,
  className = "",
}) => {
  const titleId = useId();
  const subtitleId = useId();
  const panel = useRef(null);
  const downOnBackdrop = useRef(false);

  useEffect(() => {
    const opener = document.activeElement;
    const target = initialFocus?.current ?? panel.current?.querySelector(FOCUSABLE) ?? panel.current;
    target?.focus?.();
    return () => {
      if (opener && typeof opener.focus === "function" && document.contains(opener)) opener.focus();
    };
    // Focus moves in once, when the dialog opens.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const onKeyDown = (event) => {
    if (event.key === "Escape" && dismissible) {
      event.stopPropagation();
      onClose?.();
      return;
    }
    if (event.key !== "Tab") return;
    const items = [...panel.current.querySelectorAll(FOCUSABLE)].filter((el) => !el.closest("[hidden]") && el.getAttribute("aria-hidden") !== "true");
    if (items.length === 0) {
      event.preventDefault();
      return;
    }
    const first = items[0];
    const last = items[items.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  };

  const widthClass = width ? ` ui-dialog--${width}` : "";
  return createPortal(
    <div
      className="ui-dialog__backdrop"
      onMouseDown={(event) => { downOnBackdrop.current = event.target === event.currentTarget; }}
      onMouseUp={(event) => {
        if (dismissible && downOnBackdrop.current && event.target === event.currentTarget) onClose?.();
        downOnBackdrop.current = false;
      }}
    >
      <div
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={subtitle ? subtitleId : undefined}
        tabIndex={-1}
        className={`ui-dialog${widthClass} ${className}`.trim()}
        onKeyDown={onKeyDown}
      >
        <div className="ui-dialog__head">
          <div className="ui-dialog__heading">
            {icon && (
              <span className={`ui-dialog__icon${iconTone ? ` ui-dialog__icon--${iconTone}` : ""}`}>
                <Icon name={icon} size={18} />
              </span>
            )}
            <div style={{ minWidth: 0 }}>
              <h2 id={titleId} className="ui-dialog__title">{title}</h2>
              {subtitle && <div id={subtitleId} className="ui-dialog__subtitle">{subtitle}</div>}
            </div>
          </div>
          {dismissible && (
            <button type="button" className="ui-btn ui-btn--ghost ui-btn--30 ui-btn--square" aria-label="Close" onClick={onClose}>
              <Icon name="x" size={15} />
            </button>
          )}
        </div>
        <div className="ui-dialog__body">{children}</div>
        {(footer || note) && (
          <div className="ui-dialog__foot">
            <div className="ui-dialog__note">{note}</div>
            {footer}
          </div>
        )}
      </div>
    </div>,
    document.body,
  );
};

export default Dialog;

/**
 * A question with a confirming answer (Delete, Remove all, Save), an optional second one (Don't save),
 * and Cancel. The confirming answer is the one that is drawn as such; a destructive one is red, and
 * never the only way out.
 */
export const ConfirmDialog = ({
  title,
  text,
  icon = "alertTriangle",
  iconTone,
  confirmLabel,
  confirmTone = "primary",
  confirmIcon,
  onConfirm,
  onCancel,
  cancelLabel = "Cancel",
  secondary,
  busy = false,
  width = "wide",
}) => {
  const confirmRef = useRef(null);
  const cancelRef = useRef(null);
  return (
    <Dialog
      title={title}
      icon={icon}
      iconTone={iconTone}
      width={width}
      onClose={onCancel}
      // A destructive answer is never where focus lands.
      initialFocus={confirmTone === "danger" ? cancelRef : confirmRef}
      footer={
        <>
          <button ref={cancelRef} type="button" className="ui-btn ui-btn--ghost ui-btn--38" onClick={onCancel} disabled={busy}>
            {cancelLabel}
          </button>
          {secondary && (
            <button type="button" className={`ui-btn ui-btn--${secondary.tone ?? "secondary"} ui-btn--38`} onClick={secondary.onClick} disabled={busy}>
              {secondary.label}
            </button>
          )}
          <button ref={confirmRef} type="button" className={`ui-btn ui-btn--${confirmTone} ui-btn--38`} onClick={onConfirm} disabled={busy}>
            {confirmIcon && <Icon name={confirmIcon} size={15} />}
            {confirmLabel}
          </button>
        </>
      }
    >
      {(Array.isArray(text) ? text : [text]).filter(Boolean).map((line, i) => (
        // eslint-disable-next-line react/no-array-index-key
        <p key={i} className="ui-dialog__text">{line}</p>
      ))}
    </Dialog>
  );
};
