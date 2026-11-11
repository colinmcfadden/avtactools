import React from "react";
import Icon from "../ui/Icon";
import { PACK_STATUS } from "../shell/TopBar";
import "./phoneFrame.css";

/*
 * The top of a phone's screen: the search across its whole width (TopBarSearch, which opens as the desktop's does),
 * GO at its end, and beside it the button for the menu the rest of the top bar is put away in (TopBar's `phone`).
 * It is the one part of the frame a tap on the map never hides, so its menu button is also the way back for someone
 * who cannot tap the map.
 *
 * In a Mission Pack the button carries the pack's dot (synced, saving or offline), since the words are in the menu.
 */
const PhoneBar = ({ search, menuOpen = false, onToggleMenu, onSearchStart, pack = null, packStatus = "live" }) => {
  const status = pack ? PACK_STATUS[packStatus] ?? PACK_STATUS.live : null;
  return (
    <header className="phone-bar">
      {/* The search taken up puts the menu away: its list drops where the menu is. */}
      <div className="phone-bar__search" onFocusCapture={onSearchStart}>
        {search}
      </div>
      <button
        type="button"
        className="phone-bar__menu"
        aria-label={pack ? `Menu. ${pack.name}: ${status.text}` : "Menu"}
        aria-expanded={menuOpen}
        aria-controls="phone-menu"
        onClick={onToggleMenu}
      >
        <Icon name={menuOpen ? "x" : "menu"} size={20} />
        {status && <span className={`phone-bar__dot phone-bar__dot${status.tone}`} aria-hidden="true" />}
      </button>
    </header>
  );
};

export default PhoneBar;
