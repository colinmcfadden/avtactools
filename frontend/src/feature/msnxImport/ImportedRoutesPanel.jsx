import React, { useState } from "react";
import RoutePlanSection from "./RoutePlanSection";

const EyeIcon = ({ visible }) => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
    {visible ? (
      <>
        <path d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7z" />
        <circle cx="12" cy="12" r="3" />
      </>
    ) : (
      <>
        <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94" />
        <path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19" />
        <line x1="1" y1="1" x2="23" y2="23" />
      </>
    )}
  </svg>
);

const SendIcon = () => (
  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
    <line x1="22" y1="2" x2="11" y2="13" />
    <polygon points="22 2 15 22 11 13 2 9 22 2" />
  </svg>
);

const RouteRow = ({ route, onToggleVisibility, onRemove, onForeFlight, expanded, onToggleExpand }) => (
  <div
    style={{
      display: "flex",
      alignItems: "center",
      justifyContent: "space-between",
      padding: "6px 8px",
      background: "rgba(255,255,255,0.05)",
      borderRadius: "6px",
      opacity: route.visible === false ? 0.5 : 1,
    }}
  >
    <div style={{ display: "flex", alignItems: "center", gap: "8px", minWidth: 0 }}>
      {onToggleExpand && (
        <button
          onClick={onToggleExpand}
          style={{
            background: "none",
            border: "none",
            color: "#9ca3af",
            cursor: "pointer",
            padding: 0,
            fontSize: "0.7rem",
            flexShrink: 0,
          }}
          title={expanded ? "Hide route points" : "Show route points"}
        >
          {expanded ? "▾" : "▸"}
        </button>
      )}
      <span
        style={{
          width: "12px",
          height: "12px",
          borderRadius: "50%",
          background: route.color,
          flexShrink: 0,
        }}
      />
      <span
        style={{
          overflow: "hidden",
          textOverflow: "ellipsis",
          whiteSpace: "nowrap",
          fontSize: "0.85rem",
        }}
      >
        {route.name}
      </span>
    </div>
    <div style={{ display: "flex", alignItems: "center", gap: "2px", flexShrink: 0 }}>
      {onForeFlight && (
        <button
          onClick={() => onForeFlight(route)}
          style={{
            background: "none",
            border: "none",
            color: "#00b5e2",
            cursor: "pointer",
            padding: "0 4px",
            display: "flex",
            alignItems: "center",
          }}
          title="Send to ForeFlight"
        >
          <SendIcon />
        </button>
      )}
      <button
        onClick={() => onToggleVisibility(route.id)}
        style={{
          background: "none",
          border: "none",
          color: "#9ca3af",
          cursor: "pointer",
          padding: "0 4px",
          display: "flex",
          alignItems: "center",
        }}
        title={route.visible === false ? "Show route" : "Hide route"}
      >
        <EyeIcon visible={route.visible !== false} />
      </button>
      <button
        onClick={() => onRemove(route.id)}
        style={{
          background: "none",
          border: "none",
          color: "#ef4444",
          cursor: "pointer",
          fontSize: "1rem",
          padding: "0 4px",
        }}
        title="Remove route"
      >
        ×
      </button>
    </div>
  </div>
);

const planBox = {
  padding: "6px 8px",
  background: "rgba(255,255,255,0.02)",
  borderRadius: "6px",
};

const ImportedRoutesPanel = ({
  routes,
  removeRoute,
  clearRoutes,
  exportFile,
  toggleVisibility,
  sketchedRoutes = [],
  removeSketchRoute,
  toggleSketchVisibility,
  exportSketches,
  onForeFlight,
  onSaveMissionGroup,
  onSaveSketches,
  localPointNames,
  // Two parallel plan-editing bundles (same shape) — one operating on imported
  // (mission-file) routes, one on sketched routes. Each has updateRoutePlan,
  // updatePointPlanOverride, setPointClock, updatePointName,
  // refreshRouteElevations, applyForecastWinds.
  importedPlan = {},
  sketchedPlan = {},
}) => {
  const [expandedIds, setExpandedIds] = useState(() => new Set());
  const toggleExpanded = (id) =>
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const hasImported = routes && routes.length > 0;
  const hasSketched = sketchedRoutes.length > 0;
  if (!hasImported && !hasSketched) return null;

  const fileGroups = [];
  const groupsByFileId = new Map();
  for (const route of routes || []) {
    let group = groupsByFileId.get(route.fileId);
    if (!group) {
      group = { fileId: route.fileId, fileName: route.fileName, routes: [] };
      groupsByFileId.set(route.fileId, group);
      fileGroups.push(group);
    }
    group.routes.push(route);
  }

  const groupHeaderStyle = {
    fontSize: "0.75rem",
    opacity: 0.7,
    overflow: "hidden",
    textOverflow: "ellipsis",
    whiteSpace: "nowrap",
  };

  const groupBoxStyle = {
    display: "flex",
    flexDirection: "column",
    gap: "6px",
    padding: "8px",
    background: "rgba(255,255,255,0.03)",
    borderRadius: "6px",
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "10px" }}>
      {fileGroups.map((group) => (
        <div key={group.fileId} style={groupBoxStyle}>
          <div style={groupHeaderStyle} title={group.fileName}>
            {group.fileName}
          </div>

          {group.routes.map((route) => (
            <React.Fragment key={route.id}>
              <RouteRow
                route={route}
                onToggleVisibility={toggleVisibility}
                onRemove={removeRoute}
                onForeFlight={onForeFlight}
                expanded={expandedIds.has(route.id)}
                onToggleExpand={() => toggleExpanded(route.id)}
              />
              {expandedIds.has(route.id) && (
                <div style={planBox}>
                  <RoutePlanSection
                    route={route}
                    localPointNames={localPointNames}
                    {...importedPlan}
                  />
                </div>
              )}
            </React.Fragment>
          ))}

          <div style={{ display: "flex", gap: "6px" }}>
            <button
              onClick={() => exportFile(group.fileId)}
              className="ff-action-btn ff-btn primary"
              style={{ fontSize: "0.8rem" }}
            >
              Export .msnx
            </button>
            {onSaveMissionGroup && (
              <button
                onClick={() => onSaveMissionGroup(group)}
                className="export-btn"
                style={{ fontSize: "0.8rem" }}
                title="Save this mission (with your edits) to your account"
              >
                Save
              </button>
            )}
          </div>
        </div>
      ))}

      {hasSketched && (
        <div style={groupBoxStyle}>
          <div style={groupHeaderStyle}>Sketched Routes</div>

          {sketchedRoutes.map((route) => (
            <React.Fragment key={route.id}>
              <RouteRow
                route={route}
                onToggleVisibility={toggleSketchVisibility}
                onRemove={removeSketchRoute}
                onForeFlight={onForeFlight}
                expanded={expandedIds.has(route.id)}
                onToggleExpand={() => toggleExpanded(route.id)}
              />
              {expandedIds.has(route.id) && (
                <div style={planBox}>
                  <RoutePlanSection
                    route={route}
                    localPointNames={localPointNames}
                    {...sketchedPlan}
                  />
                </div>
              )}
            </React.Fragment>
          ))}

          <div style={{ display: "flex", gap: "6px" }}>
            <button
              onClick={exportSketches}
              className="ff-action-btn ff-btn primary"
              style={{ fontSize: "0.8rem" }}
            >
              Export .msnx
            </button>
            {onSaveSketches && (
              <button
                onClick={onSaveSketches}
                className="export-btn"
                style={{ fontSize: "0.8rem" }}
                title="Save sketched routes to your account"
              >
                Save
              </button>
            )}
          </div>
        </div>
      )}

      {hasImported && (
        <button
          onClick={clearRoutes}
          className="cancel-btn"
          style={{ alignSelf: "flex-start", fontSize: "0.75rem" }}
        >
          Clear All Imported
        </button>
      )}
    </div>
  );
};

export default ImportedRoutesPanel;
