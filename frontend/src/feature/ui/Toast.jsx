import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import Icon from "./Icon";
import "./ui.css";

/*
 * Short notes at the bottom of the screen ("Saved “LZ HAWK” to your Library · Open Library") in place
 * of browser alerts. One live region announces them. A note with an action stays a little longer.
 */

const ToastContext = createContext(null);
const ICON_FOR = { ok: "check", warn: "alertTriangle", error: "alertTriangle", pack: "layers", info: "info" };

let nextId = 0;

export const ToastProvider = ({ children, duration = 5000 }) => {
  const [toasts, setToasts] = useState([]);
  const timers = useRef(new Map());

  const dismiss = useCallback((id) => {
    clearTimeout(timers.current.get(id));
    timers.current.delete(id);
    setToasts((list) => list.filter((t) => t.id !== id));
  }, []);

  /** { message, tone: ok | warn | error | pack | info, action: { label, onClick }, duration } */
  const show = useCallback(
    ({ message, tone = "ok", action, duration: ms } = {}) => {
      nextId += 1;
      const id = nextId;
      setToasts((list) => [...list.slice(-2), { id, message, tone, action }]);
      timers.current.set(id, setTimeout(() => dismiss(id), ms ?? (action ? duration + 3000 : duration)));
      return id;
    },
    [dismiss, duration],
  );

  useEffect(() => () => timers.current.forEach(clearTimeout), []);

  const value = useMemo(() => ({ show, dismiss }), [show, dismiss]);
  return (
    <ToastContext.Provider value={value}>
      {children}
      {createPortal(
        <div className="ui-toasts" role="status" aria-live="polite">
          {toasts.map((toast) => (
            <div key={toast.id} className={`ui-toast ui-toast--${toast.tone}`}>
              <span className="ui-toast__icon"><Icon name={ICON_FOR[toast.tone] ?? "info"} size={13} strokeWidth={2.2} /></span>
              <span className="ui-toast__message">{toast.message}</span>
              {toast.action && (
                <span className="ui-toast__action">
                  <button
                    type="button"
                    className="ui-link"
                    onClick={() => {
                      toast.action.onClick();
                      dismiss(toast.id);
                    }}
                  >
                    {toast.action.label}
                  </button>
                </span>
              )}
            </div>
          ))}
        </div>,
        document.body,
      )}
    </ToastContext.Provider>
  );
};

/** { show, dismiss }. Without a provider (a test of one component), notes go nowhere. */
export const useToast = () => useContext(ToastContext) ?? { show: () => null, dismiss: () => {} };
