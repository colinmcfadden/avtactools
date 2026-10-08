import React, { useMemo, useState } from "react";
import RoutePlanSection from "../msnxImport/RoutePlanSection";
import { formatDuration } from "../msnxImport/routeCalc";
import { defaultSetName, routeTotals, setSummary } from "../saveDialog/useRouteSaves";
import Avatar, { shortName } from "../ui/Avatar";
import Chip from "../ui/Chip";
import Icon from "../ui/Icon";
import MoreMenu from "../ui/MoreMenu";
import { whenShort } from "../ui/time";
import { PanelHead, PanelSection } from "./Dock";
import "./shell.css";

/*
 * The Routes panel of the dock (screen Routes): one card per route set (this session's sketched
 * routes, each imported .msnx, and in a Mission Pack each of the pack's route sets), with its save
 * state, Save, Export .msnx (with the threats as a .ths beside it if wanted), and its routes; a
 * route opens into its plan editor. Sketch a route and Import .msnx sit at the foot.
 */

const RouteRow = ({ route, expanded, onToggle, onShare, onToggleVisibility, onRemove, readOnly }) => {
  const totals = useMemo(() => routeTotals(route), [route]);
  const meta = totals
    ? `${totals.distNm.toFixed(1)} nm · ${formatDuration(totals.timeSec)}${totals.fuelLb != null ? ` · ${Math.round(totals.fuelLb)} lb` : ""}`
    : `${route.points?.length ?? 0} points`;
  const hidden = route.visible === false;
  return (
    <div className={`shell-route${hidden ? " shell-route--hidden" : ""}`}>
      <button
        type="button"
        className="ui-btn ui-btn--ghost ui-btn--26 ui-btn--square"
        aria-expanded={expanded}
        aria-label={`${expanded ? "Close" : "Open"} the plan of ${route.name}`}
        onClick={onToggle}
      >
        <Icon name={expanded ? "chevronDown" : "chevronRight"} size={15} />
      </button>
      <span className="shell-route__swatch" style={{ background: route.color }} />
      <div className="shell-route__main">
        <div className="shell-route__name">{route.name}</div>
        <div className="shell-route__meta">{meta}</div>
      </div>
      {onShare && (
        <button type="button" className="ui-btn ui-btn--ghost ui-btn--28 ui-btn--square" aria-label={`Send ${route.name} to ForeFlight`} onClick={() => onShare(route)}>
          <Icon name="send" size={15} />
        </button>
      )}
      <button
        type="button"
        className="ui-btn ui-btn--ghost ui-btn--28 ui-btn--square"
        aria-label={`${hidden ? "Show" : "Hide"} ${route.name}`}
        aria-pressed={hidden}
        onClick={() => onToggleVisibility(route.id)}
      >
        <Icon name={hidden ? "eyeOff" : "eye"} size={15} />
      </button>
      <MoreMenu
        label={`More actions for ${route.name}`}
        items={[
          { icon: "send", title: "Send to ForeFlight", onSelect: () => onShare?.(route), hidden: !onShare },
          { icon: "trash", title: "Remove route", danger: true, onSelect: () => onRemove(route.id), hidden: readOnly || !onRemove },
        ]}
      />
    </div>
  );
};

const stateChip = (set, state) => {
  if (set.pack) {
    if (set.pack.finished) return <Chip icon="lock">Read-only</Chip>;
    return <Chip tone="pack" icon="check">{set.pack.waiting ? "Waiting to send" : "Synced"}</Chip>;
  }
  if (!state.link) return <Chip hollow>Not saved yet</Chip>;
  if (state.dirty) return <Chip tone="warn">Unsaved changes</Chip>;
  return <Chip tone="ok" icon="check">{state.savedAt ? `Saved · ${whenShort(state.savedAt)}` : "Saved"}</Chip>;
};

const SetCard = ({ set, state, threatCount, expanded, onToggleRoute, actions, plan, localPointNames }) => {
  const [withThreats, setWithThreats] = useState(true);
  const [renaming, setRenaming] = useState(false);
  const [draft, setDraft] = useState("");
  const readOnly = Boolean(set.pack?.finished);
  const clean = !set.pack && state.link && !state.dirty;
  const name = state.name;
  // An imported mission goes by its file ("GOAT SUCKER.msnx") until it is given a name of its own.
  const byFile = set.kind === "mission" && !set.pack && Boolean(set.fileName) && name === defaultSetName(set);
  const title = byFile ? set.fileName : name;

  const commitRename = () => {
    setRenaming(false);
    const next = draft.trim().toUpperCase();
    if (next && next !== name) actions.rename(set, next);
  };

  return (
    <section className={`shell-set${set.pack ? " shell-set--pack" : ""}`} aria-label={title}>
      <div className="shell-card__row">
        {set.kind === "mission" && !byFile && <Icon name="file" size={14} color="#9fb0bf" />}
        {renaming ? (
          <input
            className="ui-input shell-card__name-input"
            style={{ textTransform: "uppercase" }}
            value={draft}
            // eslint-disable-next-line jsx-a11y/no-autofocus
            autoFocus
            aria-label="Name of these routes"
            maxLength={100}
            onChange={(event) => setDraft(event.target.value)}
            onBlur={commitRename}
            onKeyDown={(event) => {
              if (event.key === "Enter") commitRename();
              if (event.key === "Escape") {
                event.stopPropagation();
                setRenaming(false);
              }
            }}
          />
        ) : (
          <h3 className="shell-set__name" style={{ margin: 0 }}>{title}</h3>
        )}
        {!renaming && !readOnly && actions.rename && (
          <button
            type="button"
            className="ui-btn ui-btn--ghost ui-btn--26 ui-btn--square"
            aria-label={`Rename ${title}`}
            onClick={() => {
              setDraft(name);
              setRenaming(true);
            }}
          >
            <Icon name="pencil" size={15} />
          </button>
        )}
        <MoreMenu
          label={`More actions for ${title}`}
          items={[
            { icon: "route", title: "Sketch a route into this set", onSelect: () => actions.sketchInto?.(set), hidden: !set.pack || readOnly || !actions.sketchInto },
            { icon: "copy", title: set.pack ? "Save a copy to Library…" : "Save as…", onSelect: () => actions.saveAs(set) },
            { icon: "layers", title: "Add to Mission Pack…", onSelect: () => actions.addToPack?.(set, state.link), hidden: Boolean(set.pack) || !actions.addToPack || set.kind === "mission", disabled: !state.link, text: state.link ? undefined : "Save it first" },
            { divider: true },
            { icon: "x", title: set.pack ? "Close here" : "Close", text: set.pack ? "It stays in the pack" : "Takes these routes out of this session", onSelect: () => actions.close(set) },
          ]}
        />
      </div>
      <div className="shell-card__chips" style={{ marginTop: 7 }}>
        {set.kind === "mission" && !set.pack && <Chip tone="info" icon="download">Imported</Chip>}
        {stateChip(set, state)}
        <span className="shell-card__meta">{setSummary(set.routes)}</span>
      </div>
      {set.pack?.target && !readOnly && (
        <div className="shell-card__people">
          <Icon name="route" size={13} color="var(--pack)" />
          <span>New routes you sketch go into this set.</span>
        </div>
      )}
      {set.pack?.editedBy && (
        <div className="shell-card__people">
          <Avatar person={set.pack.editedBy} size="sm" />
          <span>{shortName(set.pack.editedBy.name)} · {whenShort(set.pack.editedAt)}</span>
        </div>
      )}
      <div className="shell-card__actions">
        {!set.pack && (
          <button
            type="button"
            className={`ui-btn ui-btn--grow${clean ? "" : " ui-btn--primary"}`}
            disabled={clean || state.saving}
            onClick={() => actions.save(set)}
          >
            <Icon name={clean ? "check" : "save"} size={15} />
            <span>{state.saving ? "Saving" : clean ? "Saved" : "Save"}</span>
          </button>
        )}
        <button type="button" className={`ui-btn${set.pack ? " ui-btn--grow" : ""}`} onClick={() => actions.export(set, { withThreats: withThreats && threatCount > 0 })}>
          <Icon name="upload" size={15} />
          <span>Export .msnx</span>
        </button>
      </div>
      {threatCount > 0 && (
        <label className="shell-set__check">
          <input type="checkbox" checked={withThreats} onChange={(event) => setWithThreats(event.target.checked)} />
          Include threats ({threatCount}) as a .ths file
        </label>
      )}
      <div className="shell-set__divider" />
      {set.routes.map((route) => (
        <React.Fragment key={route.id}>
          <RouteRow
            route={route}
            readOnly={readOnly}
            expanded={expanded.has(route.id)}
            onToggle={() => onToggleRoute(route.id)}
            onShare={actions.share}
            onToggleVisibility={(id) => actions.toggleVisibility(set, id)}
            onRemove={(id) => actions.removeRoute(set, id)}
          />
          {expanded.has(route.id) && (
            <RoutePlanSection route={route} readOnly={readOnly} localPointNames={localPointNames} {...plan(set)} />
          )}
        </React.Fragment>
      ))}
    </section>
  );
};

/**
 * `sets`: [{ key, kind, fileName, routes, pack? }]; `stateOf(key)` from useRouteSaves (in a pack: a
 * stand-in with the name). `actions`: save, saveAs, rename, close, export, share, toggleVisibility,
 * removeRoute, addToPack. `plan(set)`: the plan-editing calls for that set's routes.
 */
const RoutesDockPanel = ({
  sets,
  stateOf,
  threatCount = 0,
  actions,
  plan,
  localPointNames,
  sketch,
  pack = null,
  onImportMsnx,
  canImportMsnx = true,
  onCollapse,
}) => {
  const [expanded, setExpanded] = useState(() => new Set());
  const toggle = (id) =>
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  // A viewer is as read-only as everyone in a finished pack: no sketching into it.
  const readOnly = Boolean(pack?.readOnly || pack?.finished);

  return (
    <>
      <PanelHead
        title="Routes"
        subtitle={pack ? `${pack.name} · ${sets.length} route ${sets.length === 1 ? "set" : "sets"}` : `${sets.length} route ${sets.length === 1 ? "set" : "sets"} in this session`}
        onCollapse={onCollapse}
      />
      <div className="shell-dock__body">
        {sketch.active && (
          <div className="shell-notice shell-notice--info" role="status" style={{ marginBottom: 14 }}>
            <Icon name="route" size={15} />
            <div style={{ flex: 1 }}>
              <b>Drawing {sketch.name}.</b> Click the map to add points; right-click to add a named one.
              {sketch.points > 0 && ` ${sketch.points} ${sketch.points === 1 ? "point" : "points"} so far.`}
            </div>
          </div>
        )}
        {sets.length === 0 ? (
          <div className="shell-empty">
            {pack
              ? readOnly
                ? `No routes in ${pack.name}.`
                : `No routes in ${pack.name} yet. Sketch one and it goes into the pack.`
              : "No routes yet. Sketch one on the map, or import an AMPS mission file."}
          </div>
        ) : (
          <PanelSection label="Route sets" count={sets.length}>
            <div className="shell-list" style={{ gap: 10 }}>
              {sets.map((set) => (
                <SetCard
                  key={set.key}
                  set={set}
                  state={stateOf(set.key)}
                  threatCount={threatCount}
                  expanded={expanded}
                  onToggleRoute={toggle}
                  actions={actions}
                  plan={plan}
                  localPointNames={localPointNames}
                />
              ))}
            </div>
          </PanelSection>
        )}
      </div>
      {!readOnly && (
        <div className="shell-dock__foot">
          {sketch.active ? (
            <>
              <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" onClick={sketch.onCancel}>Cancel</button>
              <button type="button" className="ui-btn ui-btn--primary ui-btn--38 ui-btn--grow" onClick={sketch.onFinish} disabled={sketch.points < 2}>
                <Icon name="check" size={15} />
                <span>{sketch.points < 2 ? "Add two points" : "Finish route"}</span>
              </button>
            </>
          ) : (
            <>
              <button type="button" className="ui-btn ui-btn--38 ui-btn--grow" onClick={sketch.onStart} disabled={!sketch.enabled}>
                <Icon name="route" size={15} />
                <span>Sketch a route</span>
              </button>
              {!pack && (
                <button type="button" className="ui-btn ui-btn--38 ui-btn--grow" onClick={onImportMsnx} disabled={!canImportMsnx}>
                  <Icon name="download" size={15} />
                  <span>Import .msnx</span>
                </button>
              )}
            </>
          )}
        </div>
      )}
    </>
  );
};

export default RoutesDockPanel;
