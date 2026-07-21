import React, { useRef, useState } from "react";
import Draggable from "react-draggable";
import ImportedRoutesPanel from "./ImportedRoutesPanel";
import ThreatsPanel from "../threats/ThreatsPanel";

/**
 * Draggable, collapsible container for the route + threat lists, floating over
 * the map. Hidden until at least one route or threat exists.
 */
const RoutesPanel = (props) => {
  const nodeRef = useRef(null);
  const [collapsed, setCollapsed] = useState(false);

  const routeCount = (props.routes?.length || 0) + (props.sketchedRoutes?.length || 0);
  const threatCount = props.threats?.threats?.length || 0;
  if (routeCount === 0 && threatCount === 0) return null;

  return (
    <Draggable
      nodeRef={nodeRef}
      handle=".routes-panel-header"
      cancel=".collapse-btn"
      bounds="parent"
    >
      <div ref={nodeRef} className="floating-routes-panel">
        <div className="routes-panel-header">
          <span>
            Routes ({routeCount})
            {threatCount > 0 ? ` · Threats (${threatCount})` : ""}
          </span>
          <button
            className="collapse-btn"
            onClick={() => setCollapsed((c) => !c)}
            title={collapsed ? "Expand" : "Collapse"}
          >
            {collapsed ? "▼" : "▲"}
          </button>
        </div>
        {!collapsed && (
          <div className="routes-panel-body" style={{ display: "flex", flexDirection: "column", gap: "10px" }}>
            {routeCount > 0 && <ImportedRoutesPanel {...props} />}
            {props.features?.threats !== false && props.threats && (
              <ThreatsPanel {...props.threats} />
            )}
          </div>
        )}
      </div>
    </Draggable>
  );
};

export default RoutesPanel;
