import React from "react";
import Icon from "../ui/Icon";
import Menu, { MenuItem, useMenu } from "../ui/Menu";

/*
 * The top bar's Brief button and its menu (the design's "Brief menu"): what the brief is built from, Generate brief…
 * (whose last step downloads the PDF), and Present live sandtable, which walks the same steps on the map.
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
            {packName ? <>Built from everything in <b>{packName}</b>: {summary}.</> : <>Built from this session: {summary}.</>}
          </div>
        </div>
        <MenuItem
          rich
          icon={<Icon name="file" size={18} />}
          title="Generate brief…"
          text="Set up the brief: the mission flow, the constants and the details. Blanks print as write-in lines."
          onClose={menu.close}
          onSelect={onGenerate}
        />
        {onPresent && (
          <MenuItem
            rich
            icon={<Icon name="play" size={18} />}
            title="Present live sandtable"
            text="Walk the mission on the map from the start point, one step at a time, with the script on screen."
            onClose={menu.close}
            onSelect={onPresent}
          />
        )}
        {!packName && (
          <>
            <div className="ui-menu__divider" />
            <div className="ui-menu__note">
              <Icon name="info" size={15} />
              <span>Not in a pack? The brief uses the LZ/PZs and route sets open in this session.</span>
            </div>
          </>
        )}
      </Menu>
    </>
  );
};

export default BriefMenu;
