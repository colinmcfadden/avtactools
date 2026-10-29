import React from "react";
import Icon from "../ui/Icon";
import Menu, { MenuItem, useMenu } from "../ui/Menu";

/*
 * The top bar's Brief button and its menu (the design's "Brief menu"): what the brief is built from (the open pack, or
 * this session's LZ/PZs and route sets), Generate brief… (whose last step downloads the PDF), and Present live
 * sandtable, which walks the same steps on the map.
 */
const BriefMenu = ({ packName = null, summary, onGenerate, onPresent }) => {
  const menu = useMenu();
  return (
    <>
      <button type="button" className="shell-topbar__button brief-menu__button" {...menu.buttonProps}>
        <Icon name="file" size={15} />
        <span className="shell-topbar__button-label">Brief</span>
        <Icon name={menu.open ? "chevronUp" : "chevronDown"} size={13} />
      </button>
      <Menu open={menu.open} onClose={menu.close} anchorRef={menu.anchorRef} label="Mission brief" width={360} align="end">
        <div className="ui-menu__heading">
          <div className="ui-label">Mission brief</div>
          <div className="brief-menu__from">
            {packName ? <><b>{packName}</b>: {summary}</> : <>This session: {summary}</>}
          </div>
        </div>
        <MenuItem
          rich
          icon={<Icon name="file" size={18} />}
          title="Generate brief…"
          onClose={menu.close}
          onSelect={onGenerate}
        />
        {onPresent && (
          <MenuItem
            rich
            icon={<Icon name="play" size={18} />}
            title="Present live sandtable"
            onClose={menu.close}
            onSelect={onPresent}
          />
        )}
      </Menu>
    </>
  );
};

export default BriefMenu;
