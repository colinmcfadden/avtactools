import React from "react";
import { PanelHead } from "../shell/Dock";
import Chip from "../ui/Chip";
import Icon from "../ui/Icon";
import MoreMenu from "../ui/MoreMenu";
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
  pack = null,
  onImport,
  onTogglePoints,
  onSavePoints,
  onRemovePoints,
  onOpenRoutes,
  onOpenThreats,
  onCollapse,
}) => {
  const total = pointSets.length + missions.length + threatFiles.length;
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
            <span className="imports__drop-text" style={{ display: "block" }}>.msnx · .LPS · .ths · or click to choose files</span>
          </span>
        </button>

        {total === 0 && (
          <div className="shell-empty" style={{ marginTop: 14 }}>
            Nothing imported yet. AMPS mission files, local point sets and threat files you bring in are listed here.
          </div>
        )}

        {pointSets.length > 0 && (
          <Group label="Local points" count={pointSets.length}>
            {pointSets.map((set) => {
              const hidden = set.visible === false;
              const where = set.packItemId ? "pack" : set.savedId ? "library" : "session";
              return (
                <div key={set.id} className={`imports__item${hidden ? " imports__item--hidden" : ""}`}>
                  <div className="imports__item-row">
                    <span className="imports__item-icon" aria-hidden="true"><Icon name="mapPin" size={16} /></span>
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
                        { icon: "save", title: set.savedId ? "Update the Library copy" : "Save to Library", onSelect: () => onSavePoints(set), hidden: Boolean(set.packItemId) || !onSavePoints },
                        { icon: "x", title: "Remove from map", text: set.savedId ? "The Library copy is kept" : set.packItemId ? "It stays in the pack" : "It is not saved anywhere", onSelect: () => onRemovePoints(set.id) },
                      ]}
                    />
                  </div>
                  <div className="imports__item-chips">
                    <Where where={where} pack={pack} />
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
                  <Where where="locked" />
                </div>
              </div>
            ))}
          </Group>
        )}
      </div>
    </>
  );
};

export default ImportsPanel;
