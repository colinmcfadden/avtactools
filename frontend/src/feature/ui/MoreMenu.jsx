import React from "react";
import Icon from "./Icon";
import Menu, { MenuItem, useMenu } from "./Menu";

/**
 * The ⋯ button on a card or a row, and its menu. `items`: { icon, title, text, onSelect, danger,
 * disabled, hidden, divider }; a `divider` item draws a line.
 */
const MoreMenu = ({ label, items, size = 28, width = 230, className = "" }) => {
  const menu = useMenu();
  const shown = items.filter((item) => item && !item.hidden);
  if (shown.length === 0) return null;
  return (
    <>
      <button
        type="button"
        className={`ui-btn ui-btn--${size} ui-btn--square${size <= 28 ? " ui-btn--ghost" : ""}${className ? ` ${className}` : ""}`}
        aria-label={label}
        {...menu.buttonProps}
      >
        <Icon name="more" size={15} />
      </button>
      <Menu open={menu.open} onClose={menu.close} anchorRef={menu.anchorRef} label={label} align="end" width={width}>
        {shown.map((item, index) =>
          item.divider ? (
            <div key={`divider-${index}`} className="ui-menu__divider" />
          ) : (
            <MenuItem
              key={item.title}
              icon={item.icon && <Icon name={item.icon} size={15} />}
              title={item.title}
              text={item.text}
              danger={item.danger}
              disabled={item.disabled}
              onClose={menu.close}
              onSelect={item.onSelect}
            />
          ),
        )}
      </Menu>
    </>
  );
};

export default MoreMenu;
