import React from "react";
import Icon from "./Icon";
import "./ui.css";

/**
 * A state as a small pill: a dot (or an icon) and a few words. `tone`: ok, warn, info, pack, danger,
 * or none for a neutral one. `hollow` draws an empty dot ("Not saved yet").
 */
const Chip = ({ tone, icon, hollow = false, dot = !icon, title, className = "", style, children }) => (
  <span className={`ui-chip${tone ? ` ui-chip--${tone}` : ""}${className ? ` ${className}` : ""}`} title={title} style={style}>
    {icon ? (
      <Icon name={icon} size={12} strokeWidth={2} />
    ) : (
      dot && <span className={`ui-chip__dot${hollow ? " ui-chip__dot--hollow" : ""}`} />
    )}
    <span>{children}</span>
  </span>
);

export default Chip;
