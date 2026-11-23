import React, { useRef, useState } from "react";
import Dialog from "../ui/Dialog";
import Icon from "../ui/Icon";
import { BusyButton } from "../ui/Spinner";
import { formatSize } from "../imports/detectImport";
import { acceptFor } from "./documentWords";
import "./documents.css";

/*
 * A new version of a document (docs/DOCUMENTS.md §5.5): a file chosen, then uploaded under the same name and identity, the dialog
 * waiting while the server reads it (and turns a Word, Excel or PowerPoint file into a PDF, which can take a minute). A refusal is
 * said here, in the server's words where it gave some, and the file can be chosen again. `onUpload(file)` resolves once it is kept.
 * `where` says who sees the new version: the person's own devices, or everyone in a pack.
 */
const DocumentVersionDialog = ({ name, where, conversion = true, onUpload, onCancel, errorMessage }) => {
  const input = useRef(null);
  const [file, setFile] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const upload = async () => {
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      await onUpload(file);
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  };

  return (
    <Dialog
      title={`New version of “${name}”`}
      subtitle={`It replaces this version for ${where}, under the same name.`}
      icon="upload"
      onClose={busy ? undefined : onCancel}
      dismissible={!busy}
      note={conversion ? "PDF, Word, Excel or PowerPoint, up to 25 MB" : "PDF, up to 25 MB (this server does not convert Office files)"}
      footer={
        <>
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" onClick={onCancel} disabled={busy}>
            Cancel
          </button>
          <BusyButton busy={busy} icon="upload" className="ui-btn ui-btn--primary ui-btn--38" disabled={!file} onClick={upload}>
            {busy ? "Uploading" : "Upload new version"}
          </BusyButton>
        </>
      }
    >
      <input
        ref={input}
        type="file"
        hidden
        tabIndex={-1}
        aria-hidden="true"
        accept={acceptFor(conversion)}
        onChange={(event) => {
          const chosen = event.target.files?.[0] ?? null;
          event.target.value = "";
          if (chosen) {
            setFile(chosen);
            setError(null);
          }
        }}
      />
      <div className="documents-version">
        <Icon name="file" size={17} />
        <div className="documents-version__file">{file ? `${file.name} · ${formatSize(file.size)}` : "No file chosen yet"}</div>
        <button type="button" className="ui-btn ui-btn--34" disabled={busy} onClick={() => input.current?.click()}>
          {file ? "Choose another" : "Choose file"}
        </button>
      </div>
      {busy && <p className="ui-dialog__text">Reading the file{conversion ? ", and turning it into a PDF if it is an Office file" : ""}. This can take a minute.</p>}
      {error && (
        <p className="ui-dialog__text documents-version__error" role="alert">
          {error}
        </p>
      )}
    </Dialog>
  );
};

export default DocumentVersionDialog;
