import React from "react";
import Avatar, { AvatarStack } from "../ui/Avatar";
import Icon from "../ui/Icon";
import Menu, { MenuItem, useMenu } from "../ui/Menu";
import "./shell.css";

/*
 * The bar along the top of the map (docs/MENU_REDESIGN.md §3): which workspace edits go to (the
 * Library, or a Mission Pack, always in view), then Import, Library and the account. In a pack it also
 * shows who has it open and whether everything has reached everyone.
 */

const STATUS = {
  live: { tone: "", text: "Live · all changes synced" },
  polling: { tone: "", text: "All changes synced" },
  sending: { tone: "--queued", text: "Saving changes…" },
  waiting: { tone: "--queued", text: "Offline · changes will send when back" },
  loading: { tone: "--offline", text: "Opening…" },
  finished: { tone: "--finished", text: "Read-only for everyone" },
  error: { tone: "--offline", text: "Cannot reach the pack" },
};

export const IMPORT_KINDS = [
  { kind: "msnx", icon: "route", title: "Mission file", text: ".msnx · AMPS routes and points" },
  { kind: "lps", icon: "mapPin", title: "Local points", text: ".LPS · AMPS point sets" },
  { kind: "ths", icon: "diamond", title: "Threats", text: ".ths · stays in this session" },
  { kind: "overlay", icon: "image", title: "Map or overlay", text: "KMZ, GeoTIFF, image with world file · not available yet", disabled: true },
];

const TopBar = ({
  pack = null,
  packStatus = "live",
  people = [],
  renderSwitcher,
  onImport,
  canImport = {},
  onOpenLibrary,
  user,
  isAdmin = false,
  onSignOut,
  onOpenMenu,
}) => {
  const switcher = useMenu();
  const imports = useMenu();
  const account = useMenu();
  const status = STATUS[packStatus] ?? STATUS.live;
  const name = user?.name || user?.email || "Account";

  return (
    <header className={`shell-topbar${pack ? " shell-topbar--pack" : ""}`}>
      {onOpenMenu && (
        // On a phone the planning tools are a full-screen menu; this opens it.
        <button type="button" className="shell-topbar__menu" aria-label="Planning tools" onClick={onOpenMenu}>
          <Icon name="menu" size={18} />
        </button>
      )}
      <button
        type="button"
        className={`shell-topbar__switcher${pack ? " shell-topbar__switcher--pack" : ""}`}
        {...switcher.buttonProps}
        aria-label={pack ? `Workspace: ${pack.name}, a Mission Pack. Change workspace` : "Workspace: Library. Change workspace"}
      >
        <Icon name={pack ? "layers" : "library"} size={16} color={pack ? "var(--pack)" : undefined} />
        <span className="shell-topbar__switcher-name">{pack ? pack.name : "Library"}</span>
        {pack && (
          <span className={`ui-chip ${pack.status === "finished" ? "" : "ui-chip--pack"}`} style={{ height: 18, fontSize: 10 }}>
            {pack.status === "finished" ? "Finished" : "Pack"}
          </span>
        )}
        <Icon name={switcher.open ? "chevronUp" : "chevronDown"} size={14} />
      </button>
      {renderSwitcher?.({ open: switcher.open, close: switcher.close, anchorRef: switcher.anchorRef })}

      {pack ? (
        <>
          {people.length > 0 && <AvatarStack people={people} max={3} />}
          <span className={`shell-topbar__status shell-topbar__status${status.tone}`}>
            <span className="shell-topbar__status-dot" />
            {status.text}
          </span>
        </>
      ) : (
        <span className="shell-topbar__context">Personal workspace · saved items go to your Library</span>
      )}

      <div className="shell-topbar__end">
        <button type="button" className="shell-topbar__button" {...imports.buttonProps}>
          <Icon name="download" size={15} />
          <span className="shell-topbar__button-label">Import</span>
          <Icon name="chevronDown" size={13} />
        </button>
        <Menu open={imports.open} onClose={imports.close} anchorRef={imports.anchorRef} label="Import" width={340} align="end">
          <div className="ui-menu__heading"><div className="ui-label">Import</div></div>
          {IMPORT_KINDS.map((entry) => (
            <MenuItem
              key={entry.kind}
              rich
              icon={<Icon name={entry.icon} size={18} />}
              title={entry.title}
              text={entry.text}
              disabled={entry.disabled || canImport[entry.kind] === false}
              onClose={imports.close}
              onSelect={() => onImport?.(entry.kind)}
            />
          ))}
          <div className="ui-menu__divider" />
          <div className="ui-menu__note">
            <Icon name="upload" size={15} />
            <span>Or drop files anywhere on the map. We work out the type.</span>
          </div>
        </Menu>

        <button type="button" className="shell-topbar__button" onClick={onOpenLibrary} style={{ paddingRight: 11 }}>
          <Icon name="folder" size={15} />
          <span className="shell-topbar__button-label">Library</span>
        </button>
        <div className="shell-topbar__divider" />
        <button type="button" className="shell-topbar__account" {...account.buttonProps} aria-label={`Account: ${name}`}>
          <Avatar person={user} title="" />
          <span className="shell-topbar__account-name">{name}</span>
          <Icon name="chevronDown" size={13} />
        </button>
        <Menu open={account.open} onClose={account.close} anchorRef={account.anchorRef} label="Account" width={250} align="end">
          <div className="ui-menu__heading">
            <div style={{ fontSize: 13, fontWeight: 700, color: "#f3f5f7" }}>{name}</div>
            {user?.email && <div style={{ fontSize: 12, color: "var(--ff-text-muted)", marginTop: 2 }}>{user.email}</div>}
          </div>
          <div className="ui-menu__divider" />
          {isAdmin && (
            <MenuItem
              icon={<Icon name="building" size={15} />}
              title="Admin dashboard"
              onClose={account.close}
              onSelect={() => window.open(`${(process.env.REACT_APP_API_URL || "").replace(/\/api\/?$/, "")}/admin`, "_blank", "noopener")}
            />
          )}
          <MenuItem icon={<Icon name="logOut" size={15} />} title="Sign out" onClose={account.close} onSelect={onSignOut} />
        </Menu>
      </div>
    </header>
  );
};

export default TopBar;
