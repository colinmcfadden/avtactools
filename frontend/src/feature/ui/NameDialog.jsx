import React, { useEffect, useId, useRef, useState } from "react";
import Dialog from "./Dialog";
import Icon from "./Icon";

/*
 * Asks for one name, in place of the browser's prompt (a route being finished, a route point being
 * designated, a set or pack item being renamed). The field is filled in and selected; Enter confirms,
 * Esc cancels, and an empty name is refused in words unless `allowEmpty`.
 */
const NameDialog = ({
  title,
  subtitle,
  icon = "pencil",
  label = "Name",
  initialName = "",
  hint = "Press Enter to confirm.",
  confirmLabel = "OK",
  upperCase = false,
  allowEmpty = false,
  onConfirm,
  onCancel,
  children,
}) => {
  const fieldId = useId();
  const input = useRef(null);
  const [name, setName] = useState(initialName);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    input.current?.select();
  }, []);

  const submit = async () => {
    const value = upperCase ? name.trim().toUpperCase() : name.trim();
    if (!value && !allowEmpty) {
      setError("Give it a name.");
      input.current?.focus();
      return;
    }
    setBusy(true);
    try {
      await onConfirm(value);
    } catch (err) {
      setError(err?.userMessage || err?.message || "That did not work. Try again.");
      setBusy(false);
    }
  };

  return (
    <Dialog
      title={title}
      subtitle={subtitle}
      icon={icon}
      onClose={busy ? undefined : onCancel}
      dismissible={!busy}
      initialFocus={input}
      footer={
        <>
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" onClick={onCancel} disabled={busy}>Cancel</button>
          <button type="button" className="ui-btn ui-btn--primary ui-btn--38" onClick={submit} disabled={busy}>
            <Icon name="check" size={15} />
            {confirmLabel}
          </button>
        </>
      }
    >
      <form
        onSubmit={(event) => {
          event.preventDefault();
          submit();
        }}
      >
        <div className="ui-field">
          <label htmlFor={fieldId} className="ui-field__label">{label}</label>
          <input
            ref={input}
            id={fieldId}
            type="text"
            className={`ui-input${error ? " ui-input--error" : ""}`}
            style={upperCase ? { textTransform: "uppercase" } : undefined}
            value={name}
            maxLength={100}
            autoComplete="off"
            spellCheck={false}
            aria-invalid={Boolean(error)}
            aria-describedby={`${fieldId}-hint`}
            onChange={(event) => {
              setName(event.target.value);
              setError(null);
            }}
          />
          <div id={`${fieldId}-hint`} className={`ui-field__hint${error ? " ui-field__hint--error" : ""}`}>
            {error ?? hint}
          </div>
        </div>
        {children}
        <button type="submit" hidden tabIndex={-1} aria-hidden="true" />
      </form>
    </Dialog>
  );
};

export default NameDialog;
