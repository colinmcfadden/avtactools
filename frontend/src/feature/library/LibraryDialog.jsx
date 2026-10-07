import React, { useEffect, useMemo, useRef, useState } from "react";
import { nextFreeName } from "../saveDialog/SaveDialog";
import Chip from "../ui/Chip";
import Dialog, { ConfirmDialog } from "../ui/Dialog";
import Icon from "../ui/Icon";
import MoreMenu from "../ui/MoreMenu";
import NameDialog from "../ui/NameDialog";
import { dateAndTime } from "../ui/time";
import { useToast } from "../ui/Toast";
import { duplicateRecord, renameRecord } from "./libraryApi";
import "./library.css";

/*
 * The Library (docs/MENU_REDESIGN.md §5, screen Library): everything the person has saved, in three
 * tabs. Opening one adds it to this session and never replaces unsaved work; one already open says
 * so. Each row's ⋯ renames, duplicates, adds to a Mission Pack or deletes (asked first: it is gone
 * from every device). Saving happens on the things themselves, never here.
 */

const TABS = [
  { key: "lz", label: "LZ/PZ", icon: "hexagon", noun: ["saved LZ/PZ", "saved LZ/PZs"], search: "Search LZ/PZs by name" },
  { key: "routes", label: "Routes", icon: "route", noun: ["saved route set", "saved route sets"], search: "Search routes by name" },
  { key: "points", label: "Local points", icon: "mapPin", noun: ["saved point set", "saved point sets"], search: "Search point sets by name" },
];

const SORTS = [
  { key: "updated", label: "Recently updated" },
  { key: "name", label: "Name A to Z" },
];

const metaFor = (kind, entry) => {
  const updated = `Updated ${dateAndTime(entry.updated_at)}`;
  if (kind === "routes") return `${entry.kind === "mission" ? `Mission file${entry.file_name ? ` · ${entry.file_name}` : ""}` : "Sketched routes"} · ${updated}`;
  if (kind === "points") return `${entry.point_count ?? 0} points · ${updated}`;
  return updated;
};

const LibraryDialog = ({ initialTab = "lz", sources, onClose, onAddToPack, onOpenPacks, openLabel = "Open", openingLabel = "Opening", subtitle }) => {
  const { show } = useToast();
  const [tab, setTab] = useState(initialTab);
  const [query, setQuery] = useState("");
  const [sort, setSort] = useState("updated");
  const [renaming, setRenaming] = useState(null);
  const [deleting, setDeleting] = useState(null);
  const [busyId, setBusyId] = useState(null);
  const searchRef = useRef(null);
  const tabRefs = useRef({});

  useEffect(() => {
    Object.values(sources).forEach((source) => source.refresh?.());
    // Fetch once when the Library opens.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const source = sources[tab];
  const meta = TABS.find((t) => t.key === tab);
  const rows = useMemo(() => {
    const wanted = query.trim().toLocaleUpperCase();
    const list = (source.entries ?? []).filter((entry) => !wanted || (entry.name || "").toLocaleUpperCase().includes(wanted));
    return [...list].sort((a, b) =>
      sort === "name" ? (a.name || "").localeCompare(b.name || "") : String(b.updated_at).localeCompare(String(a.updated_at)),
    );
  }, [source.entries, query, sort]);

  const open = async (entry) => {
    setBusyId(entry.id);
    try {
      await source.onOpen(entry);
      onClose();
    } catch (err) {
      show({ tone: "error", message: `“${entry.name}” could not be opened. ${err?.message ?? ""}`.trim() });
    } finally {
      setBusyId(null);
    }
  };

  const duplicate = async (entry) => {
    setBusyId(entry.id);
    const name = nextFreeName(entry.name, source.entries);
    try {
      await duplicateRecord(tab, entry, name);
      await source.refresh?.();
      show({ message: `Made “${name}”, a copy of “${entry.name}”` });
    } catch (err) {
      show({ tone: "error", message: `“${entry.name}” could not be copied. Check the connection and try again.` });
    } finally {
      setBusyId(null);
    }
  };

  const onTabKey = (event) => {
    const index = TABS.findIndex((t) => t.key === tab);
    const step = event.key === "ArrowRight" ? 1 : event.key === "ArrowLeft" ? -1 : 0;
    if (!step) return;
    event.preventDefault();
    const next = TABS[(index + step + TABS.length) % TABS.length].key;
    setTab(next);
    tabRefs.current[next]?.focus();
  };

  const total = (source.entries ?? []).length;
  return (
    <>
      <Dialog
        title="Library"
        subtitle={subtitle ?? "Everything you have saved. Opening one adds it to this session."}
        icon="folder"
        className="library"
        onClose={onClose}
        initialFocus={searchRef}
        note={
          <span>
            {total} {total === 1 ? meta.noun[0] : meta.noun[1]}
            {onOpenPacks && (
              <>
                {" · Working with a team? "}
                <button
                  type="button"
                  className="ui-link"
                  onClick={() => {
                    onClose();
                    onOpenPacks();
                  }}
                >
                  Open a Mission Pack
                </button>
              </>
            )}
          </span>
        }
        footer={
          <button type="button" className="ui-btn ui-btn--38" onClick={onClose}>
            Close
          </button>
        }
      >
        <div className="library__tabs" role="tablist" aria-label="What to show" onKeyDown={onTabKey}>
          {TABS.map((t) => (
            <button
              key={t.key}
              ref={(el) => {
                tabRefs.current[t.key] = el;
              }}
              type="button"
              role="tab"
              id={`library-tab-${t.key}`}
              aria-selected={tab === t.key}
              aria-controls="library-panel"
              tabIndex={tab === t.key ? 0 : -1}
              className={`library__tab${tab === t.key ? " library__tab--active" : ""}`}
              onClick={() => setTab(t.key)}
            >
              {t.label}
              <span className="library__tab-count">{(sources[t.key].entries ?? []).length}</span>
            </button>
          ))}
        </div>
        <div className="library__tools">
          <div className="library__search">
            <Icon name="search" size={15} />
            <input
              ref={searchRef}
              type="search"
              className="ui-input"
              placeholder={meta.search}
              aria-label={meta.search}
              value={query}
              onChange={(event) => setQuery(event.target.value)}
            />
          </div>
          <select className="ui-input library__sort" aria-label="Sort by" value={sort} onChange={(event) => setSort(event.target.value)}>
            {SORTS.map((s) => (
              <option key={s.key} value={s.key}>{s.label}</option>
            ))}
          </select>
        </div>
        <div className="library__list" id="library-panel" role="tabpanel" aria-labelledby={`library-tab-${tab}`}>
          {source.loading && rows.length === 0 ? (
            <div className="library__empty">Loading…</div>
          ) : rows.length === 0 ? (
            <div className="library__empty">
              {query
                ? `Nothing matches “${query}”.`
                : tab === "lz"
                  ? "Nothing saved yet. Save an LZ/PZ from its card in the LZ/PZ panel."
                  : tab === "routes"
                    ? "No saved routes yet. Save a set from the Routes panel."
                    : "No saved point sets yet. Import an .LPS file and save it from the Imports panel."}
            </div>
          ) : (
            rows.map((entry) => {
              const isOpen = source.openIds?.has(entry.id);
              return (
                <div key={entry.id} className="library__row">
                  <span className="library__row-icon" aria-hidden="true">
                    <Icon name={tab === "routes" && entry.kind === "mission" ? "file" : meta.icon} size={16} />
                  </span>
                  <div className="library__row-main">
                    <div className="library__row-name">{entry.name}</div>
                    <div className="library__row-meta">{metaFor(tab, entry)}</div>
                  </div>
                  {isOpen && <Chip tone="ok" dot={false}>{source.openWords ?? "Open in session"}</Chip>}
                  <button
                    type="button"
                    className="ui-btn ui-btn--34 library__open"
                    disabled={isOpen || busyId === entry.id}
                    aria-label={`${openLabel} ${entry.name}`}
                    onClick={() => open(entry)}
                  >
                    {busyId === entry.id ? openingLabel : openLabel}
                  </button>
                  <MoreMenu
                    label={`More actions for ${entry.name}`}
                    size={34}
                    items={[
                      { icon: "pencil", title: "Rename", onSelect: () => setRenaming(entry) },
                      { icon: "copy", title: "Duplicate", onSelect: () => duplicate(entry) },
                      { icon: "layers", title: "Add to Mission Pack…", onSelect: () => onAddToPack(tab, entry), hidden: !onAddToPack || (tab === "routes" && entry.kind === "mission"), text: undefined },
                      { divider: true },
                      { icon: "trash", title: "Delete", danger: true, onSelect: () => setDeleting(entry) },
                    ]}
                  />
                </div>
              );
            })
          )}
        </div>
      </Dialog>
      {renaming && (
        <NameDialog
          title={`Rename “${renaming.name}”`}
          initialName={renaming.name}
          confirmLabel="Rename"
          hint="Press Enter to rename. It changes on every device you use."
          onCancel={() => setRenaming(null)}
          onConfirm={async (name) => {
            if (name !== renaming.name) {
              await renameRecord(tab, renaming.id, name);
              await source.refresh?.();
              source.onRenamed?.(renaming, name);
            }
            setRenaming(null);
          }}
        />
      )}
      {deleting && (
        <ConfirmDialog
          title={`Delete “${deleting.name}”?`}
          text="This removes it from your Library on every device you use. Copies you added to a Mission Pack are not affected."
          icon="trash"
          iconTone="danger"
          confirmLabel="Delete"
          confirmTone="danger"
          onCancel={() => setDeleting(null)}
          onConfirm={async () => {
            const entry = deleting;
            setDeleting(null);
            try {
              await source.onDelete(entry);
              show({ message: `Deleted “${entry.name}”` });
            } catch (err) {
              show({ tone: "error", message: `“${entry.name}” could not be deleted. Check the connection and try again.` });
            }
          }}
        />
      )}
    </>
  );
};

export default LibraryDialog;
