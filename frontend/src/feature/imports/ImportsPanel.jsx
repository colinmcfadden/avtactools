import React, { useState } from "react";
import { PanelHead } from "../shell/Dock";
import Chip from "../ui/Chip";
import Icon from "../ui/Icon";
import MoreMenu from "../ui/MoreMenu";
import NameDialog from "../ui/NameDialog";
import PointSetPoints from "../localPoints/PointSetPoints";
import { leftOutWords } from "../overlays/parseKmz";
import "./imports.css";

/*
 * The Imports panel of the dock (screen ImportsPanel): the index of what has been brought in, grouped
 * by type, each saying where it lives (this session, the Library, the open pack). It does not repeat
 * editing: routes are edited under Routes and threats under Threats, and those rows link there.
 */

const plural = (n, one, many = `${one}s`) => `${Number(n).toLocaleString("en-US")} ${n === 1 ? one : many}`;

const Where = ({ where, pack }) => {
  if (where === "pack") return <Chip tone="pack" icon="layers">{pack?.name ?? "Pack"}</Chip>;
  if (where === "library") return <Chip tone="info" dot={false}>Library</Chip>;
  if (where === "locked") return <Chip tone="warn" icon="lock">Session only</Chip>;
  return <Chip dot={false}>This session</Chip>;
};

const Group = ({ label, count, children }) => (
  <>
    <div className="imports__group">
      <div className="ui-label">
        {label}
        <span className="ui-label__count">{count}</span>
      </div>
    </div>
    <div className="shell-list">{children}</div>
  </>
);

const ImportsPanel = ({
  pointSets = [],
  missions = [],
  threatFiles = [],
  overlays = [],
  onToggleOverlay,
  onSaveOverlay,
  onZoomOverlay,
  onRemoveOverlay,
  threatsSaved = false,
  pack = null,
  onImport,
  onTogglePoints,
  onSavePoints,
  onRemovePoints,
  onOpenRoutes,
  onOpenThreats,
  onCollapse,
  canEditPoints = false,
  canExportPoints = false,
  onNewPoint,
  onEditPoint,
  onRemovePoint,
  onExportPoints,
  onRenamePoints,
}) => {
  const [expanded, setExpanded] = useState(() => new Set());
  const [renaming, setRenaming] = useState(null);
  const toggleExpanded = (id) =>
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  const total = pointSets.length + missions.length + threatFiles.length + overlays.length;
  return (
    <>
      <PanelHead title="Imports" subtitle={`${plural(total, "item")} on the map`} onCollapse={onCollapse}>
        <span style={{ flex: 1 }} />
      </PanelHead>
      <div className="shell-dock__body">
        <button type="button" className="imports__drop" onClick={onImport}>
          <Icon name="upload" size={20} />
          <span style={{ minWidth: 0 }}>
            <span className="imports__drop-title" style={{ display: "block" }}>Drop files here, or anywhere on the map</span>
            <span className="imports__drop-text" style={{ display: "block" }}>.msnx · .fpl · .gpx · .LPS · .ths · .kmz · or click to choose files</span>
          </span>
        </button>

        {canEditPoints && (
          <div className="imports__new">
            <button type="button" className="ui-btn ui-btn--34" onClick={() => onNewPoint()}>
              <Icon name="plus" size={15} />
              New local point
            </button>
          </div>
        )}

        {total === 0 && (
          <div className="shell-empty" style={{ marginTop: 14 }}>
            Nothing imported yet.
          </div>
        )}

        {pointSets.length > 0 && (
          <Group label="Local points" count={pointSets.length}>
            {pointSets.map((set) => {
              const hidden = set.visible === false;
              const where = set.packItemId ? "pack" : set.savedId ? "library" : "session";
              const open = expanded.has(set.id);
              const empty = (set.points?.length ?? 0) === 0;
              const behind = !set.packItemId && set.savedId && set.dirty;
              return (
                <div key={set.id} className={`imports__item${hidden ? " imports__item--hidden" : ""}`}>
                  <div className="imports__item-row">
                    <button type="button" className="imports__expand" aria-expanded={open} aria-label={`${open ? "Hide" : "Show"} the points in ${set.name}`} onClick={() => toggleExpanded(set.id)}>
                      <Icon name={open ? "chevronDown" : "chevronRight"} size={16} />
                    </button>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div className="imports__item-name">
                        <span className="imports__item-swatch" style={{ background: set.color }} />
                        <span>{set.name}</span>
                      </div>
                      <div className="imports__item-meta">{plural(set.points?.length ?? 0, "point")}</div>
                    </div>
                    <button
                      type="button"
                      className="ui-btn ui-btn--ghost ui-btn--28 ui-btn--square"
                      aria-label={`${hidden ? "Show" : "Hide"} ${set.name}`}
                      aria-pressed={hidden}
                      onClick={() => onTogglePoints(set.id)}
                    >
                      <Icon name={hidden ? "eyeOff" : "eye"} size={15} />
                    </button>
                    <MoreMenu
                      label={`More actions for ${set.name}`}
                      items={[
                        { icon: "plus", title: "Add a point", onSelect: () => onNewPoint(set.id), hidden: !canEditPoints },
                        { icon: "pencil", title: "Rename", onSelect: () => setRenaming(set), hidden: !canEditPoints },
                        // The server refuses a set with no points, so an empty one is not offered.
                        { icon: "save", title: set.savedId ? "Update the Library copy" : "Save to Library", onSelect: () => onSavePoints(set), hidden: Boolean(set.packItemId) || !onSavePoints || empty },
                        { icon: "download", title: "Export as .LPS", text: "For AMPS", onSelect: () => onExportPoints("lps", set.name, set.points), hidden: !canExportPoints || empty },
                        { icon: "download", title: "Export as GPX", text: "Waypoints, for other apps", onSelect: () => onExportPoints("gpx", set.name, set.points), hidden: !canExportPoints || empty },
                        { icon: "x", title: "Remove from map", text: set.savedId ? "The Library copy is kept" : set.packItemId ? "It stays in the pack" : "It is not saved anywhere", onSelect: () => onRemovePoints(set.id) },
                      ]}
                    />
                  </div>
                  <div className="imports__item-chips">
                    <Where where={where} pack={pack} />
                    {behind && <Chip tone="warn" dot={false}>Changes not saved</Chip>}
                    {empty && <Chip dot={false}>No points yet</Chip>}
                  </div>
                  {open && (
                    <PointSetPoints
                      set={set}
                      editable={canEditPoints}
                      canExport={canExportPoints}
                      onAdd={(setId, group) => onNewPoint(setId, group)}
                      onEdit={onEditPoint}
                      onRemove={onRemovePoint}
                      onExport={onExportPoints}
                    />
                  )}
                </div>
              );
            })}
          </Group>
        )}

        {overlays.length > 0 && (
          <Group label="Map overlays" count={overlays.length}>
            {overlays.map((overlay) => {
              const hidden = !overlay.visible;
              const leftOut = leftOutWords(overlay.doc.leftOut);
              return (
                <div key={overlay.id} className={`imports__item${hidden ? " imports__item--hidden" : ""}`}>
                  <div className="imports__item-row">
                    <span className="imports__item-icon" aria-hidden="true"><Icon name="image" size={16} /></span>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div className="imports__item-name"><span>{overlay.name}</span></div>
                      <div className="imports__item-meta">{overlay.doc.summary}</div>
                      {leftOut.length > 0 && <div className="imports__item-meta">Left out: {leftOut.join(", ")}</div>}
                    </div>
                    <button
                      type="button"
                      className="ui-btn ui-btn--ghost ui-btn--28 ui-btn--square"
                      aria-label={`${hidden ? "Show" : "Hide"} ${overlay.name}`}
                      aria-pressed={hidden}
                      onClick={() => onToggleOverlay(overlay.id)}
                    >
                      <Icon name={hidden ? "eyeOff" : "eye"} size={15} />
                    </button>
                    <MoreMenu
                      label={`More actions for ${overlay.name}`}
                      items={[
                        { icon: "crosshair", title: "Go to on the map", onSelect: () => onZoomOverlay(overlay), hidden: !overlay.doc.bounds },
                        { icon: "save", title: "Save to Library", onSelect: () => onSaveOverlay(overlay), hidden: Boolean(overlay.savedId) || !onSaveOverlay },
                        { icon: "x", title: "Remove from map", text: overlay.savedId ? "The Library copy is kept" : "It is not saved anywhere", onSelect: () => onRemoveOverlay(overlay.id) },
                      ]}
                    />
                  </div>
                  <div className="imports__item-chips">
                    <Where where={overlay.savedId ? "library" : "session"} />
                  </div>
                </div>
              );
            })}
          </Group>
        )}

        {missions.length > 0 && (
          <Group label="Mission files" count={missions.length}>
            {missions.map((mission) => (
              <div key={mission.key} className="imports__item">
                <div className="imports__item-row">
                  <span className="imports__item-icon" aria-hidden="true"><Icon name="file" size={16} /></span>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div className="imports__item-name"><span>{mission.fileName}</span></div>
                    <div className="imports__item-meta">{mission.summary}</div>
                  </div>
                  <button type="button" className="ui-link" style={{ marginRight: 8 }} onClick={onOpenRoutes}>Open in Routes</button>
                </div>
                <div className="imports__item-chips">
                  <Where where={mission.saved ? "library" : "session"} />
                </div>
              </div>
            ))}
          </Group>
        )}

        {threatFiles.length > 0 && (
          <Group label="Threat files" count={threatFiles.length}>
            {threatFiles.map((entry) => (
              <div key={entry.id} className="imports__item">
                <div className="imports__item-row">
                  <span className="imports__item-icon" aria-hidden="true"><Icon name="diamond" size={16} /></span>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div className="imports__item-name"><span>{entry.fileName}</span></div>
                    <div className="imports__item-meta">{plural(entry.count, "threat")} · listed under Threats</div>
                  </div>
                  <button type="button" className="ui-link" style={{ marginRight: 8 }} onClick={onOpenThreats}>Open in Threats</button>
                </div>
                <div className="imports__item-chips">
                  <Where where={threatsSaved ? "library" : "locked"} />
                </div>
              </div>
            ))}
          </Group>
        )}
      </div>
      {renaming && (
        <NameDialog
          title={`Rename “${renaming.name}”`}
          initialName={renaming.name}
          confirmLabel="Rename"
          upperCase
          onCancel={() => setRenaming(null)}
          onConfirm={(name) => {
            onRenamePoints(renaming.id, name);
            setRenaming(null);
          }}
        />
      )}
    </>
  );
};

export default ImportsPanel;
