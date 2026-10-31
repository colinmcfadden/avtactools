import React, { useId, useRef, useState } from "react";
import Dialog from "../../ui/Dialog";
import Icon from "../../ui/Icon";

/*
 * What a pack is for, the line under its name in the Pack panel: a few sentences, 2,000 characters at
 * most (the server's limit), and it may be left empty. `onSave(text)` may throw an Error whose message
 * is shown under the field, so a refusal is read where the text was typed.
 */

const MAX_DESCRIPTION = 2000;

const PackDescriptionDialog = ({ packName, initial = "", onSave, onCancel }) => {
  const id = useId();
  const field = useRef(null);
  const [text, setText] = useState(initial);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const save = async () => {
    setBusy(true);
    try {
      await onSave(text.trim());
    } catch (err) {
      setError(err?.message || "That did not work. Try again.");
      setBusy(false);
      field.current?.focus();
    }
  };

  return (
    <Dialog
      title={`Description of ${packName}`}
      subtitle="Everyone in the pack sees it under the pack's name."
      icon="pencil"
      onClose={busy ? undefined : onCancel}
      dismissible={!busy}
      initialFocus={field}
      footer={
        <>
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" onClick={onCancel} disabled={busy}>Cancel</button>
          <button type="button" className="ui-btn ui-btn--primary ui-btn--38" onClick={save} disabled={busy}>
            <Icon name="check" size={15} />
            Save
          </button>
        </>
      }
    >
      <div className="ui-field">
        <label className="ui-field__label" htmlFor={id}>Description</label>
        <textarea
          ref={field}
          id={id}
          className={`ui-input${error ? " ui-input--error" : ""}`}
          rows={4}
          maxLength={MAX_DESCRIPTION}
          placeholder="What it is for, so people know what they are opening."
          value={text}
          aria-invalid={Boolean(error)}
          aria-describedby={`${id}-hint`}
          onChange={(event) => {
            setText(event.target.value);
            setError(null);
          }}
        />
        <div id={`${id}-hint`} className={`ui-field__hint${error ? " ui-field__hint--error" : ""}`}>
          {error ?? `${text.length.toLocaleString("en-US")} of 2,000 characters. Leave it empty for none.`}
        </div>
      </div>
    </Dialog>
  );
};

export default PackDescriptionDialog;
