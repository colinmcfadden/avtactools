import { useMemo, useRef, useState } from "react";
import Draggable from "react-draggable";
import "./ActiveLzWindow.css";

const STATUS_LABELS = {
  targeted: "TARGET SET",
  analyzed: "ANALYZED",
  analyzing: "ANALYZING",
  draft: "DRAFT",
};

function asDiagramList(diagrams) {
  if (Array.isArray(diagrams)) return diagrams;
  if (diagrams && typeof diagrams === "object") return Object.values(diagrams);
  return [];
}

function diagramId(diagram) {
  return diagram?.id ?? diagram?.diagramId ?? diagram?.clientId;
}

function diagramTarget(diagram) {
  return (
    diagram?.target?.mgrs ||
    diagram?.target?.grid ||
    diagram?.mgrs ||
    diagram?.gridInput ||
    diagram?.mapData?.mgrs ||
    "Target not set"
  );
}

function diagramStatus(diagram) {
  const status = String(
    diagram?.status ?? diagram?.lifecycle ?? diagram?.analysis?.status ?? "draft"
  ).toLowerCase();

  return STATUS_LABELS[status] || status.replace(/[_-]/g, " ").toUpperCase();
}

function isDirty(diagram) {
  if (diagram?.savedId == null) return true;
  if (typeof diagram?.isDirty === "boolean") return diagram.isDirty;
  return Boolean(diagram?.dirty);
}

/**
 * A compact, map-overlay diagram switcher.
 *
 * The parent should mount this in a positioned map container. `bounds="parent"`
 * keeps the window inside that container while the header is dragged.
 */
export default function ActiveLzWindow({
  diagrams,
  activeDiagramId,
  onSelect,
  onClose,
  onSave,
  onRemove,
  canSaveActive = true,
  isSaving = false,
  initialPosition = { x: 16, y: 92 },
}) {
  const nodeRef = useRef(null);
  const [collapsed, setCollapsed] = useState(false);
  const diagramList = useMemo(() => asDiagramList(diagrams), [diagrams]);
  const activeDiagram = diagramList.find(
    (diagram) => diagramId(diagram) === activeDiagramId,
  );
  const activeDirty = isDirty(activeDiagram);
  const hasSavedActiveDiagram = activeDiagram?.savedId != null;
  const saveLabel = hasSavedActiveDiagram
    ? activeDirty
      ? "UPDATE"
      : "SAVED"
    : "SAVE";
  const saveDisabled =
    !activeDiagram ||
    !canSaveActive ||
    isSaving ||
    (hasSavedActiveDiagram && !activeDirty);

  return (
    <Draggable
      bounds="parent"
      defaultPosition={initialPosition}
      handle=".active-lz-window__handle"
      cancel="button, [data-no-drag]"
      nodeRef={nodeRef}
    >
      <aside
        ref={nodeRef}
        className={`active-lz-window${collapsed ? " is-collapsed" : ""}`}
        aria-label="LZ/PZ diagrams"
      >
        <header className="active-lz-window__header active-lz-window__handle">
          <span className="active-lz-window__layer-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" focusable="false">
              <path d="m12 3 8.5 4.5L12 12 3.5 7.5 12 3Z" />
              <path d="m3.5 12 8.5 4.5 8.5-4.5" />
              <path d="m3.5 16.5 8.5 4.5 8.5-4.5" />
            </svg>
          </span>
          <div className="active-lz-window__heading">
            <span>ACTIVE LZ / PZ</span>
            <small>{diagramList.length} IN SESSION</small>
          </div>
          <button
            type="button"
            className="active-lz-window__collapse"
            onClick={() => setCollapsed((value) => !value)}
            aria-expanded={!collapsed}
            aria-label={collapsed ? "Expand LZ/PZ diagram list" : "Collapse LZ/PZ diagram list"}
            title={collapsed ? "Expand diagram list" : "Collapse diagram list"}
          >
            <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <path d={collapsed ? "m7 10 5 5 5-5" : "m7 14 5-5 5 5"} />
            </svg>
          </button>
          {typeof onClose === "function" && (
            <button
              type="button"
              className="active-lz-window__close"
              onClick={onClose}
              aria-label="Close LZ/PZ diagram list"
              title="Close diagram list"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                <path d="m6 6 12 12M18 6 6 18" />
              </svg>
            </button>
          )}
        </header>

        {!collapsed && (
          <>
            <div className="active-lz-window__body" data-no-drag>
              {diagramList.length === 0 ? (
                <p className="active-lz-window__empty">Set a target to initialize an LZ/PZ.</p>
              ) : (
                <ul className="active-lz-window__list">
                  {diagramList.map((diagram, index) => {
                    const id = diagramId(diagram);
                    const active = id === activeDiagramId;
                    const dirty = isDirty(diagram);
                    const title = diagram?.name || diagram?.title || `LZ/PZ ${index + 1}`;

                    return (
                      <li key={id ?? `${title}-${index}`}>
                        <button
                          type="button"
                          className={`active-lz-window__row${active ? " is-active" : ""}`}
                          aria-pressed={active}
                          onClick={() => onSelect?.(id, diagram)}
                        >
                          <span className="active-lz-window__row-marker" aria-hidden="true" />
                          <span className="active-lz-window__row-content">
                            <span className="active-lz-window__row-title">{title}</span>
                            <span className="active-lz-window__row-target">{diagramTarget(diagram)}</span>
                          </span>
                          <span className="active-lz-window__row-meta">
                            <span className={`active-lz-window__status status-${diagramStatus(diagram).toLowerCase().replace(/\s+/g, "-")}`}>
                              {diagramStatus(diagram)}
                            </span>
                            <span
                              className={`active-lz-window__persistence${dirty ? " is-dirty" : " is-saved"}`}
                              title={dirty ? "Unsaved changes" : "Saved"}
                            >
                              {dirty ? "UNSAVED" : "SAVED"}
                            </span>
                          </span>
                        </button>
                      </li>
                    );
                  })}
                </ul>
              )}
            </div>

            <div className="active-lz-window__actions" data-no-drag>
              <button
                type="button"
                className="active-lz-window__action active-lz-window__action--save"
                onClick={() => onSave?.(activeDiagram)}
                disabled={saveDisabled}
                title={
                  !canSaveActive
                    ? "Analyze the active LZ/PZ before saving it."
                    : hasSavedActiveDiagram && !activeDirty
                      ? "The active LZ/PZ is already saved."
                      : hasSavedActiveDiagram
                        ? "Save updates to the active LZ/PZ."
                        : "Save the active LZ/PZ."
                }
              >
                <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                  <path d="M5 3h11l3 3v15H5V3Z" />
                  <path d="M8 3v6h8V3M8 21v-7h8v7" />
                </svg>
                <span>{isSaving ? "SAVING" : saveLabel}</span>
              </button>
              <button
                type="button"
                className="active-lz-window__action active-lz-window__action--remove"
                onClick={() => onRemove?.(activeDiagram)}
                disabled={!activeDiagram || isSaving}
                title="Remove the active LZ/PZ from this session"
              >
                <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                  <path d="M4 7h16M9 7V4h6v3M7 7l1 13h8l1-13M10 11v5M14 11v5" />
                </svg>
                <span>REMOVE</span>
              </button>
            </div>
          </>
        )}
      </aside>
    </Draggable>
  );
}
