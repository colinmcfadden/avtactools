import React, { useMemo, useState } from "react";
import Icon from "../ui/Icon";
import MoreMenu from "../ui/MoreMenu";
import { toMgrs } from "../../utils/mgrs";
import { pointGroups } from "./customPoints";

/*
 * The points of one set, by group, under its row in the Imports panel. A real .LPS can hold thousands,
 * so only so many rows are drawn, and a search finds the rest.
 */

const MAX_ROWS = 200;
const SEARCH_FROM = 30;

const exportItems = (canExport, onExport, name, points) => [
  { icon: "download", title: "Export as .LPS", text: "For AMPS", hidden: !canExport, onSelect: () => onExport("lps", name, points) },
  { icon: "download", title: "Export as GPX", text: "Waypoints, for other apps", hidden: !canExport, onSelect: () => onExport("gpx", name, points) },
];

const PointSetPoints = ({ set, editable, canExport, onAdd, onEdit, onRemove, onExport }) => {
  const [query, setQuery] = useState("");
  const wanted = query.trim().toLocaleUpperCase();
  const points = useMemo(
    () => (wanted ? set.points.filter((point) => `${point.name} ${point.description ?? ""}`.toLocaleUpperCase().includes(wanted)) : set.points),
    [set.points, wanted],
  );
  const shown = points.slice(0, MAX_ROWS);
  const groups = useMemo(() => pointGroups(shown), [shown]);
  const allGroups = useMemo(() => pointGroups(set.points), [set.points]);

  return (
    <div className="points-list">
      {set.points.length >= SEARCH_FROM && (
        <input className="ui-input points-list__search" value={query} placeholder="Search these points" aria-label={`Search the points in ${set.name}`} onChange={(event) => setQuery(event.target.value)} />
      )}
      {groups.map((group) => (
        <div key={group.name}>
          <div className="points-list__group">
            <span className="points-list__group-name">{group.name}</span>
            <span className="ui-label__count">{allGroups.find((one) => one.name === group.name)?.points.length}</span>
            <span style={{ flex: 1 }} />
            {editable && (
              <button type="button" className="ui-btn ui-btn--ghost ui-btn--28 ui-btn--square" aria-label={`Add a point to ${group.name}`} title="Add a point to this group" onClick={() => onAdd(set.id, group.name)}>
                <Icon name="plus" size={14} />
              </button>
            )}
            <MoreMenu label={`Export the group ${group.name}`} items={exportItems(canExport, onExport, `${set.name} ${group.name}`, allGroups.find((one) => one.name === group.name)?.points ?? [])} />
          </div>
          {group.points.map((point) => (
            <div key={point.id} className="points-list__row">
              <div style={{ flex: 1, minWidth: 0 }}>
                <div className="points-list__name">{point.name || "Unnamed"}</div>
                <div className="imports__item-meta">
                  {[toMgrs(point.lat, point.lon), typeof point.elevationFt === "number" ? `${Math.round(point.elevationFt).toLocaleString("en-US")} ft` : null].filter(Boolean).join(" · ")}
                </div>
              </div>
              {editable && (
                <button type="button" className="ui-btn ui-btn--ghost ui-btn--28 ui-btn--square" aria-label={`Edit ${point.name}`} onClick={() => onEdit(set, point)}>
                  <Icon name="pencil" size={14} />
                </button>
              )}
              <MoreMenu
                label={`More actions for ${point.name}`}
                items={[
                  ...exportItems(canExport, onExport, point.name || "point", [point]),
                  { icon: "trash", title: "Delete point", danger: true, hidden: !editable, onSelect: () => onRemove(set, point) },
                ]}
              />
            </div>
          ))}
        </div>
      ))}
      {points.length > MAX_ROWS && <div className="imports__item-meta">{MAX_ROWS} of {points.length.toLocaleString("en-US")} shown. Search to find the rest.</div>}
      {points.length === 0 && <div className="imports__item-meta">{wanted ? "No point matches that." : "No points in this set yet."}</div>}
    </div>
  );
};

export default PointSetPoints;
