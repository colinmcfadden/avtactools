import React from "react";
import Avatar, { AvatarStack } from "../ui/Avatar";
import Icon from "../ui/Icon";
import Menu, { MenuItem, useMenu } from "../ui/Menu";
import "./shell.css";

/*
 * The bar along the top of the map (docs/MENU_REDESIGN.md §3): which workspace edits go to (the
 * Library, or a Mission Pack, always in view), the search (a grid or a lat/long, and its share
 * button: `searchSlot`), then Import, Library and the account. In a pack it also
 * shows who has it open and whether everything has reached everyone.
 *
 * On a phone (850 px and below) the search is the top of the screen (PhoneBar) and the rest of this bar is
 * a menu under it, shown by the search bar's menu button (`phone`), with the planning tools' full-screen
 * menu among its buttons.
 */

export const PACK_STATUS = {
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
  { kind: "routefile", icon: "route", title: "Flight plan or GPX", text: ".fpl, .gpx · opens as sketched routes" },
  { kind: "lps", icon: "mapPin", title: "Local points", text: ".LPS · AMPS point sets" },
  { kind: "ths", icon: "diamond", title: "Threats", text: ".ths · AMPS threats, saved to your Library" },
  { kind: "overlay", icon: "image", title: "Map overlay", text: ".kmz, .kml · drawn over the map" },
];

const TopBar = ({
  undo,
  pack = null,
  packStatus = "live",
  people = [],
  renderSwitcher,
  // The switcher's menu (useMenu), when the caller holds it so it can open it from elsewhere.
  switcher: heldSwitcher,
  onImport,
  canImport = {},
  onOpenLibrary,
  // What goes before Import: the mission brief's button, for an account that has it.
  briefSlot = null,
  // Between the workspace and the buttons: the search (TargetField). A phone has its own over the map.
  searchSlot = null,
  user,
  isAdmin = false,
  // The LZ survey book: admins only (docs/LZ_SURVEYS.md).
  onOpenSurveys,
  onSignOut,
  onOpenMenu,
  // On a phone the bar is put away under the search (PhoneBar), whose menu button shows it: { open, onDone }.
  // `onDone` closes it once something in it has been chosen.
  phone = null,
}) => {
  const ownSwitcher = useMenu();
  const switcher = heldSwitcher ?? ownSwitcher;
  const imports = useMenu();
  const account = useMenu();
  const status = PACK_STATUS[packStatus] ?? PACK_STATUS.live;
  const name = user?.name || user?.email || "Account";

  const done = phone?.onDone;
  const then = (action) => (...args) => {
    done?.();
    return action?.(...args);
  };

  const switcherButton = renderSwitcher ? (
    <button
      type="button"
      className={`shell-topbar__switcher${pack ? " shell-topbar__switcher--pack" : ""}`}
      {...switcher.buttonProps}
      aria-label={pack ? `Workspace: ${pack.name}, a Mission Pack. Change workspace` : "Workspace: Library. Change workspace"}
    >
      <Icon name={pack ? "layers" : "library"} size={16} color={pack ? "var(--pack)" : undefined} />
      {/* In the phone's menu it stands over a button that opens the Library: it says which of the two it is. */}
      {phone && <span className="phone-menu__kind">Workspace</span>}
      <span className="shell-topbar__switcher-name">{pack ? pack.name : "Library"}</span>
      {pack && (
        <span className={`ui-chip ${pack.status === "finished" ? "" : "ui-chip--pack"}`} style={{ height: 18 }}>
          {pack.status === "finished" ? "Finished" : "Pack"}
        </span>
      )}
      <Icon name={switcher.open ? "chevronUp" : "chevronDown"} size={14} />
    </button>
  ) : (
    <span className="shell-topbar__switcher" style={{ cursor: "default" }}>
      <Icon name="library" size={16} />
      {phone && <span className="phone-menu__kind">Workspace</span>}
      <span className="shell-topbar__switcher-name">Library</span>
    </span>
  );
  const switcherMenu = renderSwitcher?.({ open: switcher.open, close: switcher.close, anchorRef: switcher.anchorRef });

  const presence = pack ? (
    <>
      {people.length > 0 && (
        <span title={`${people.map((p) => p.name).join(", ")} ${people.length === 1 ? "has" : "have"} this pack open`}>
          <AvatarStack people={people} max={3} />
        </span>
      )}
      <span className={`shell-topbar__status shell-topbar__status${status.tone}`}>
        <span className="shell-topbar__status-dot" />
        {status.text}
      </span>
    </>
  ) : null;

  const importButton = (
    <>
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
            onSelect={then(() => onImport?.(entry.kind))}
          />
        ))}
        <div className="ui-menu__divider" />
        <div className="ui-menu__note">
          <Icon name="upload" size={15} />
          <span>{phone ? "Choose a file. We work out the type." : "Or drop files anywhere on the map. We work out the type."}</span>
        </div>
      </Menu>
    </>
  );

  const libraryButton = (
    <button type="button" className="shell-topbar__button" onClick={then(onOpenLibrary)} style={{ paddingRight: 11 }}>
      <Icon name="folder" size={15} />
      <span className="shell-topbar__button-label">Library</span>
    </button>
  );

  const accountButton = (
    <>
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
            onSelect={then(() => window.open(`${(import.meta.env.VITE_API_URL || "").replace(/\/api\/?$/, "")}/admin`, "_blank", "noopener"))}
          />
        )}
        {onOpenSurveys && (
          <MenuItem icon={<Icon name="hexagon" size={15} />} title="LZ survey books" onClose={account.close} onSelect={then(onOpenSurveys)} />
        )}
        <MenuItem icon={<Icon name="logOut" size={15} />} title="Sign out" onClose={account.close} onSelect={then(onSignOut)} />
      </Menu>
    </>
  );

  if (phone) {
    // The same workspace, Brief, Import, Library and account, laid out as a menu under the phone's search bar: who and
    // where first, then what can be opened. Kept mounted while closed (out of reach: inert), so a menu opened from
    // elsewhere (the Library's "Open a Mission Pack") finds its button.
    return (
      <div
        id="phone-menu"
        role="region"
        aria-label="Menu"
        className={`phone-menu${phone.open ? " phone-menu--open" : ""}${pack ? " phone-menu--pack" : ""}`}
        inert={!phone.open}
      >
        <div className="phone-menu__row">
          {switcherButton}
          {switcherMenu}
          {accountButton}
        </div>
        {presence && <div className="phone-menu__row phone-menu__row--status">{presence}</div>}
        <div className="phone-menu__row phone-menu__row--actions">
          {onOpenMenu && (
            <button type="button" className="shell-topbar__button" aria-label="Planning tools" onClick={then(onOpenMenu)}>
              <Icon name="sliders" size={15} />
              <span className="shell-topbar__button-label">Tools</span>
            </button>
          )}
          {briefSlot}
          {importButton}
          {libraryButton}
        </div>
      </div>
    );
  }

  return (
    <header className={`shell-topbar${pack ? " shell-topbar--pack" : ""}`}>
      {switcherButton}
      {switcherMenu}

      {presence ?? <span className="shell-topbar__context">Personal workspace</span>}

      {searchSlot && <div className="shell-topbar__search">{searchSlot}</div>}

      <div className="shell-topbar__end">
        {undo && (
          <div className="shell-topbar__undo">
            <button type="button" className="shell-topbar__button" onClick={undo.undo} disabled={!undo.canUndo} title={undo.undoTitle} aria-label={undo.undoTitle}>
              <Icon name="undo" size={15} />
            </button>
            <button type="button" className="shell-topbar__button" onClick={undo.redo} disabled={!undo.canRedo} title={undo.redoTitle} aria-label={undo.redoTitle}>
              <Icon name="redo" size={15} />
            </button>
          </div>
        )}
        {briefSlot}
        {importButton}
        {libraryButton}
        <div className="shell-topbar__divider" />
        {accountButton}
      </div>
    </header>
  );
};

export default TopBar;
