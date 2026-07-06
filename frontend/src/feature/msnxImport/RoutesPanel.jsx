import React, { useRef, useState } from "react";
import Draggable from "react-draggable";
import ImportedRoutesPanel from "./ImportedRoutesPanel";

/**
 * Draggable, collapsible container for the route list, floating over the map.
 * Hidden entirely until at least one route (imported or sketched) exists.
 */
const RoutesPanel = (props) => {
  const nodeRef = useRef(null);
  const [collapsed, setCollapsed] = useState(false);

  const routeCount = (props.routes?.length || 0) + (props.sketchedRoutes?.length || 0);
  if (routeCount === 0) return null;

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
          <div className="routes-panel-body">
            <ImportedRoutesPanel {...props} />
          </div>
        )}
      </div>
    </Draggable>
  );
};

export default RoutesPanel;
