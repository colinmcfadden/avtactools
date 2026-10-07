import React, { useEffect, useId, useMemo, useRef, useState } from "react";
import Dialog from "../ui/Dialog";
import Icon from "../ui/Icon";
import { dateAtTime } from "../ui/time";

/*
 * The one way something is first saved to the Library (an LZ/PZ, a set of routes, a copy of a pack
 * item), in place of the browser's prompt: a name, already filled in and selected; what is and is not
 * saved; where it goes. Enter saves and Esc cancels. A name already used in the Library offers to keep
 * both (this one becomes "NAME 2") or to replace the saved one. Later saves of the same thing are
 * silent (docs/MENU_REDESIGN.md §4).
 */

const norm = (name) => (name ?? "").trim().toLocaleUpperCase();

/** "NAME 2", or the next number free among the names already used. */
export const nextFreeName = (name, existing) => {
  const base = name.trim();
  const taken = new Set((existing ?? []).map((entry) => norm(entry.name)));
  if (!taken.has(norm(base))) return base;
  for (let n = 2; n < 1000; n += 1) {
    const candidate = `${base} ${n}`;
    if (!taken.has(norm(candidate))) return candidate;
  }
  return `${base} ${Date.now()}`;
};

const SaveDialog = ({
  title,
  subtitle,
  icon = "save",
  initialName = "",
  contents = [],
  note = "Saves to your Library",
  existing = [],
  kindPhrase = "an item",
  saveLabel = "Save",
  onSave,
  onCancel,
}) => {
  const fieldId = useId();
  const input = useRef(null);
  const [name, setName] = useState(initialName);
  const [choice, setChoice] = useState("both");
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    input.current?.select();
  }, []);

  const clash = useMemo(() => {
    const wanted = norm(name);
    return wanted ? (existing ?? []).find((entry) => norm(entry.name) === wanted) ?? null : null;
  }, [name, existing]);

  const submit = async () => {
    const trimmed = name.trim();
    if (!trimmed) {
      setError("Give it a name.");
      input.current?.focus();
      return;
    }
    if (trimmed.length > 100) {
      setError("A name can be 100 characters at most.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      if (clash && choice === "replace") await onSave(trimmed, { replace: clash });
      else await onSave(clash ? nextFreeName(trimmed, existing) : trimmed, { replace: null });
    } catch (err) {
      setError(err?.userMessage || "It could not be saved. Check the connection and try again.");
      setBusy(false);
    }
  };

  const primaryLabel = clash ? (choice === "replace" ? "Replace" : "Save as new") : saveLabel;
  const keepBothName = clash ? nextFreeName(name, existing) : "";

  return (
    <Dialog
      title={title}
      subtitle={clash ? "Name is already used." : subtitle}
      icon={icon}
      width={clash ? "wide" : undefined}
      onClose={busy ? undefined : onCancel}
      dismissible={!busy}
      initialFocus={input}
      note={note && (<span style={{ display: "inline-flex", alignItems: "center", gap: 6 }}><Icon name="folder" size={13} />{note}</span>)}
      footer={
        <>
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" onClick={onCancel} disabled={busy}>Cancel</button>
          <button type="button" className="ui-btn ui-btn--primary ui-btn--38" onClick={submit} disabled={busy}>
            <Icon name="save" size={15} />
            {busy ? "Saving" : primaryLabel}
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
          <label htmlFor={fieldId} className="ui-field__label">Name</label>
          <input
            ref={input}
            id={fieldId}
            type="text"
            className={`ui-input${error ? " ui-input--error" : clash ? " ui-input--warn" : ""}`}
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
          <div
            id={`${fieldId}-hint`}
            className={`ui-field__hint${error ? " ui-field__hint--error" : clash ? " ui-field__hint--warn" : ""}`}
          >
            {error ??
              (clash
                ? `You already have ${kindPhrase} with this name${clash.updatedAt ? `, last saved ${dateAtTime(clash.updatedAt)}` : ""}.`
                : "Press Enter to save. You can rename it any time.")}
          </div>
        </div>
        {clash && (
          <div role="radiogroup" aria-label="What to do with the name" style={{ display: "flex", flexDirection: "column", gap: 8, marginTop: 12 }}>
            <label className={`ui-choice${choice === "both" ? " ui-choice--selected" : ""}`}>
              <input type="radio" name={`${fieldId}-dup`} checked={choice === "both"} onChange={() => setChoice("both")} />
              <span>
                <span className="ui-choice__title">Keep both</span>
                <span className="ui-choice__text">Save this one as “{keepBothName}”.</span>
              </span>
            </label>
            <label className={`ui-choice${choice === "replace" ? " ui-choice--selected" : ""}`}>
              <input type="radio" name={`${fieldId}-dup`} checked={choice === "replace"} onChange={() => setChoice("replace")} />
              <span>
                <span className="ui-choice__title">Replace the saved one</span>
                <span className="ui-choice__text">Overwrite it with this version. The old version is not kept.</span>
              </span>
            </label>
          </div>
        )}
        {contents.length > 0 && !clash && (
          <>
            <div className="ui-dialog__gap" />
            <div className="ui-label" style={{ marginBottom: 8 }}>What is saved</div>
            <div className="ui-dialog__list">
              {contents.map((row) => (
                <div key={row.text} className="ui-dialog__list-row">
                  <Icon name={row.icon ?? "check"} size={14} color={row.icon === "info" ? "#8a94a1" : "#82d4ae"} style={{ marginTop: 2 }} />
                  <span>{row.text}</span>
                </div>
              ))}
            </div>
          </>
        )}
        {/* A submit button keeps Enter working in every browser; the visible one is in the footer. */}
        <button type="submit" hidden tabIndex={-1} aria-hidden="true" />
      </form>
    </Dialog>
  );
};

export default SaveDialog;
