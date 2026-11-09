import React, { useEffect, useId, useRef, useState } from "react";
import { ConfirmDialog } from "../ui/Dialog";
import { BusyButton } from "../ui/Spinner";
import Icon from "../ui/Icon";
import { useToast } from "../ui/Toast";
import { fetchAsset, surveyFailure } from "./surveyApi";

/*
 * A survey's photos (docs/LZ_SURVEYS.md §3.2, §5): the field's north and best-approach photos, and others, each with its caption,
 * the heading it looks down and when it was taken. The server turns each the right way up, shrinks it and strips every bit of
 * metadata (a phone's GPS position and serial number included) before it is kept. Taking one off the survey keeps it on the
 * server: a published page may show it.
 */

export const PHOTO_KINDS = [
  { id: "photo_north", label: "North" },
  { id: "photo_approach", label: "Best approach" },
  { id: "photo_other", label: "Other" },
];

const kindLabel = (id) => PHOTO_KINDS.find((k) => k.id === id)?.label ?? (id === "view_3d" ? "3D view" : id);

/** The picture, fetched with the session's token (an <img> cannot send it) and shown from an object URL. */
export const Thumb = ({ url, alt }) => {
  const [src, setSrc] = useState(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let objectUrl = null;
    let alive = true;
    fetchAsset(url)
      .then((blob) => {
        if (!alive) return;
        objectUrl = URL.createObjectURL(blob);
        setSrc(objectUrl);
      })
      .catch(() => alive && setFailed(true));
    return () => {
      alive = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [url]);
  if (failed) return <div className="survey-photo__thumb survey-photo__thumb--empty">Could not load</div>;
  return src ? <img className="survey-photo__thumb" src={src} alt={alt} /> : <div className="survey-photo__thumb survey-photo__thumb--empty" aria-hidden="true" />;
};

/** A text field that keeps what is typed and commits it on leaving, when it changed. */
const CommitField = ({ label, value, onCommit, placeholder, inputMode, width }) => {
  const id = useId();
  const [text, setText] = useState(value ?? "");
  useEffect(() => setText(value ?? ""), [value]);
  return (
    <label className="survey-photo__field" htmlFor={id} style={width ? { width } : undefined}>
      <span className="ui-field__label">{label}</span>
      <input
        id={id}
        className="ui-input ui-input--36"
        value={text}
        placeholder={placeholder}
        inputMode={inputMode}
        onChange={(event) => setText(event.target.value)}
        onBlur={() => {
          if (text !== (value ?? "")) onCommit(text);
        }}
      />
    </label>
  );
};

const headingText = (deg) => (deg === null || deg === undefined ? "" : String(Math.round(deg)).padStart(3, "0"));

const SurveyPhotos = ({ assets = [], onUpload, onUpdate, onRemove, defaultTakenOn = "" }) => {
  const { show } = useToast();
  const fileInput = useRef(null);
  const [kind, setKind] = useState("photo_north");
  const [uploading, setUploading] = useState(false);
  const [removing, setRemoving] = useState(null);
  const photos = assets.filter((a) => a.kind !== "view_3d");

  const upload = async (files) => {
    const list = [...(files ?? [])];
    if (!list.length) return;
    setUploading(true);
    for (const file of list) {
      try {
        await onUpload(file, { kind, caption: "", heading_deg: kind === "photo_north" ? 0 : "", taken_on: defaultTakenOn });
      } catch (err) {
        show({ message: `${file.name}: ${surveyFailure(err, "it could not be uploaded.")}`, tone: "error" });
      }
    }
    setUploading(false);
    if (fileInput.current) fileInput.current.value = "";
  };

  const update = async (id, meta) => {
    try {
      await onUpdate(id, meta);
    } catch (err) {
      show({ message: surveyFailure(err, "The photo could not be changed."), tone: "error" });
    }
  };

  return (
    <section className="survey-panel" aria-labelledby="survey-photos">
      <div className="survey-panel__head">
        <h3 id="survey-photos" className="survey-panel__title">Photos</h3>
        <div className="survey-actions">
          <select className="ui-input ui-input--36 survey-select" aria-label="Kind of photo to upload" value={kind} onChange={(event) => setKind(event.target.value)}>
            {PHOTO_KINDS.map((k) => <option key={k.id} value={k.id}>{k.label}</option>)}
          </select>
          <BusyButton className="ui-btn ui-btn--34" icon="upload" busy={uploading} onClick={() => fileInput.current?.click()}>
            {uploading ? "Uploading…" : "Upload"}
          </BusyButton>
          <input
            ref={fileInput}
            type="file"
            accept="image/jpeg,image/png"
            multiple
            hidden
            data-testid="survey-photo-file"
            onChange={(event) => upload(event.target.files)}
          />
        </div>
      </div>
      <p className="survey-muted">JPEG or PNG, up to 20 MB. Location and camera details are removed before it is kept.</p>
      {photos.length === 0 ? (
        <p className="survey-muted">No photos yet. The page prints the north photo and the one down the best approach.</p>
      ) : (
        <ul className="survey-photos">
          {photos.map((asset) => (
            <li key={asset.id} className="survey-photo">
              <Thumb url={asset.url} alt={asset.caption || `${kindLabel(asset.kind)} photo`} />
              <div className="survey-photo__meta">
                <label className="survey-photo__field">
                  <span className="ui-field__label">Kind</span>
                  <select className="ui-input ui-input--36 survey-select" value={asset.kind} onChange={(event) => update(asset.id, { kind: event.target.value })}>
                    {PHOTO_KINDS.map((k) => <option key={k.id} value={k.id}>{k.label}</option>)}
                  </select>
                </label>
                <CommitField label="Caption" value={asset.caption} onCommit={(caption) => update(asset.id, { caption })} />
                <CommitField
                  label="Looking (° true)"
                  value={headingText(asset.heading_deg)}
                  inputMode="numeric"
                  width={120}
                  onCommit={(text) => update(asset.id, { heading_deg: text.trim() === "" ? null : text.trim() })}
                />
                <CommitField label="Taken" value={asset.taken_on} placeholder="e.g. OCT 2023" width={120} onCommit={(taken_on) => update(asset.id, { taken_on })} />
                <div className="survey-muted">{asset.width} × {asset.height} · {Math.round(asset.size_bytes / 1024)} KB</div>
                <button type="button" className="ui-btn ui-btn--28 ui-btn--ghost" onClick={() => setRemoving(asset)}>
                  <Icon name="trash" size={13} />
                  Take off the survey
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}
      {removing && (
        <ConfirmDialog
          title="Take this photo off the survey?"
          text="It stays on the server, so a page already published still shows it."
          confirmLabel="Take it off"
          confirmTone="danger"
          onCancel={() => setRemoving(null)}
          onConfirm={async () => {
            try {
              await onRemove(removing.id);
            } catch (err) {
              show({ message: surveyFailure(err, "The photo could not be taken off."), tone: "error" });
            }
            setRemoving(null);
          }}
        />
      )}
    </section>
  );
};

export default SurveyPhotos;
