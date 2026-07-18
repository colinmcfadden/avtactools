import React from "react";

/**
 * A small chevron button sitting on the control-panel seam that toggles the
 * panel between its full width and the collapsed icon rail. Positioned at the
 * sidebar's right edge; the chevron points the direction the click will move
 * the panel (◀ collapse when open, ▶ expand when collapsed).
 */
const SidebarCollapseToggle = ({ collapsed, onToggle, width }) => (
  <button
    type="button"
    className="sidebar-collapse-toggle"
    style={{ left: width - 13 }}
    onClick={onToggle}
    title={collapsed ? "Expand panel" : "Collapse panel"}
    aria-label={collapsed ? "Expand control panel" : "Collapse control panel"}
    aria-expanded={!collapsed}
  >
    <svg
      viewBox="0 0 24 24"
      width="15"
      height="15"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.5"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {collapsed ? (
        <polyline points="9 6 15 12 9 18" />
      ) : (
        <polyline points="15 6 9 12 15 18" />
      )}
    </svg>
  </button>
);

export default SidebarCollapseToggle;
