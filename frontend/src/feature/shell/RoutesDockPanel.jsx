import React, { useEffect, useMemo, useState } from "react";
import { exportableRoutes } from "../msnxImport/foreflight";
import RoutePlanSection from "../msnxImport/RoutePlanSection";
import { ROUTE_COLOR_CHOICES, routeColorName } from "../msnxImport/routeSetOps";
import { formatDuration } from "../msnxImport/routeCalc";
import { flotLengthKm } from "../flot/flots";
import { defaultSetName, routeTotals, setSummary } from "../saveDialog/useRouteSaves";
import Avatar, { shortName } from "../ui/Avatar";
import Chip from "../ui/Chip";
import Icon from "../ui/Icon";
import Menu, { useMenu } from "../ui/Menu";
import MoreMenu from "../ui/MoreMenu";
import { whenShort } from "../ui/time";
import { PanelHead, PanelSection } from "./Dock";
import { CombineRoutesDialog, MoveRouteDialog } from "./RouteSetDialogs";
import "./shell.css";

/*
 * The Routes panel of the dock (screen Routes): one card per route set (this session's sketched
 * routes, each imported .msnx, and in a Mission Pack each of the pack's route sets), with its save
 * state, Save, Export .msnx (with the threats as a .ths beside it if wanted), and its routes; a
 * route opens into its plan editor. Sketch a route and Import .msnx sit at the foot.
 *
 * A route is sent to ForeFlight from its row, and a whole set from its title row, beside the pencil:
 * the same export screen for both. Sending changes nothing, so both stay in a read-only pack.
 *
 * A route's colour is changed from its dot; from its menu it is joined onto another route of its set
 * (Combine with…) or moved to another set, or a new one (Move to another set…). New route set, at the
 * foot, makes an empty set that the next sketches go into. None of these in a read-only pack, and an
 * imported mission's routes only change colour: which routes it has is its file's.
 */

const routeMeta = (route) => {
  const totals = routeTotals(route);
  return totals
    ? `${totals.distNm.toFixed(1)} nm · ${formatDuration(totals.timeSec)}${totals.fuelLb != null ? ` · ${Math.round(totals.fuelLb)} lb` : ""}`
    : `${route.points?.length ?? 0} points`;
};

const sameColor = (a, b) => String(a ?? "").toLowerCase() === String(b ?? "").toLowerCase();

const RouteRow = ({ route, expanded, onToggle, onShare, onToggleVisibility, onRemove, onColor, onCombine, onMove, readOnly }) => {
  const meta = useMemo(() => routeMeta(route), [route]);
  const colors = useMenu();
  const colorName = routeColorName(route.color);
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
      {onColor ? (
        <>
          <button
            type="button"
            className="ui-btn ui-btn--ghost ui-btn--26 ui-btn--square shell-route__color"
            aria-label={`Colour of ${route.name}${colorName ? `: ${colorName}` : ""}. Change it`}
            {...colors.buttonProps}
          >
            <span className="shell-route__swatch" style={{ background: route.color }} />
          </button>
          <Menu open={colors.open} onClose={colors.close} anchorRef={colors.anchorRef} label={`Colour of ${route.name}`} width={172}>
            <div className="shell-swatches">
              {ROUTE_COLOR_CHOICES.map((choice) => (
                <button
                  key={choice.color}
                  type="button"
                  role="menuitemradio"
                  aria-checked={sameColor(choice.color, route.color)}
                  aria-label={choice.name}
                  title={choice.name}
                  className="shell-swatch"
                  style={{ background: choice.color }}
                  onClick={() => {
                    colors.close();
                    onColor(route.id, choice.color);
                  }}
                />
              ))}
            </div>
          </Menu>
        </>
      ) : (
        <span className="shell-route__swatch" style={{ background: route.color }} />
      )}
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
          { icon: "dot", title: "Colour…", onSelect: colors.toggle, hidden: !onColor },
          { icon: "route", title: "Combine with…", onSelect: () => onCombine?.(route), hidden: !onCombine },
          { icon: "layers", title: "Move to another set…", onSelect: () => onMove?.(route), hidden: !onMove },
          { divider: true },
          { icon: "trash", title: "Remove route", danger: true, onSelect: () => onRemove(route.id), hidden: readOnly || !onRemove },
        ]}
      />
    </div>
  );
};

const FlotRow = ({ flot, readOnly, onFlip, onRemove }) => (
  <div className="shell-route">
    <span className="shell-route__swatch" style={{ background: "#2f8fff" }} />
    <div className="shell-route__main">
      <div className="shell-route__name">FLOT</div>
      <div className="shell-route__meta">{flotLengthKm(flot).toFixed(1)} km</div>
    </div>
    {!readOnly && (
      <>
        <button type="button" className="ui-btn ui-btn--ghost ui-btn--28" aria-label="Flip the scallops of this FLOT to the other side" onClick={() => onFlip(flot.id)}>
          <Icon name="refresh" size={15} />
          <span>Flip</span>
        </button>
        <button type="button" className="ui-btn ui-btn--ghost ui-btn--28 ui-btn--square" aria-label="Remove this FLOT" onClick={() => onRemove(flot.id)}>
          <Icon name="trash" size={15} />
        </button>
      </>
    )}
  </div>
);

const stateChip = (set, state) => {
  if (set.pack) {
    if (set.pack.finished) return <Chip icon="lock">Read-only</Chip>;
    return <Chip tone="pack" icon="check">{set.pack.waiting ? "Waiting to send" : "Synced"}</Chip>;
  }
  if (!state.link) return <Chip hollow>Not saved yet</Chip>;
  if (state.dirty) return <Chip tone="warn">Unsaved changes</Chip>;
  return <Chip tone="ok" icon="check">{state.savedAt ? `Saved · ${whenShort(state.savedAt)}` : "Saved"}</Chip>;
};

const SetCard = ({ set, state, threatCount, expanded, onToggleRoute, actions, plan, localPointNames, pointSearch, canChooseForSketches, onAsk }) => {
  const [withThreats, setWithThreats] = useState(true);
  const [renaming, setRenaming] = useState(false);
  const [draft, setDraft] = useState("");
  const [saidWhy, setSaidWhy] = useState(false);
  const readOnly = Boolean(set.pack?.finished);
  const clean = !set.pack && state.link && !state.dirty;
  // A mission's routes are its file's: none joins, leaves or arrives. Their colours are this app's.
  const sketched = set.kind !== "mission";
  const empty = set.routes.length === 0 && !(set.flots?.length > 0);
  // Where the next sketched route goes: the pack's own say, or this session's with more than one set to choose from.
  const target = set.pack ? set.pack.target : Boolean(set.target);
  const name = state.name;
  // An imported mission goes by its file ("GOAT SUCKER.msnx") until it is given a name of its own.
  const byFile = set.kind === "mission" && !set.pack && Boolean(set.fileName) && name === defaultSetName(set);
  const title = byFile ? set.fileName : name;
  // A set is sent as its routes: with none of two points there is nothing to send, and the button says so.
  const sendable = exportableRoutes(set.routes).length;
  const sendOff = sendable > 0 ? null : set.routes.length > 0 ? "No route of this set has two points yet" : "No routes in this set to send";
  // What a tap was told is put away once there is something to send, so it is not said again unasked.
  useEffect(() => {
    if (!sendOff) setSaidWhy(false);
  }, [sendOff]);

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
        {!renaming && actions.shareSet && (
          <button
            type="button"
            className="ui-btn ui-btn--ghost ui-btn--26 ui-btn--square"
            aria-label={`Send ${title} to ForeFlight`}
            // Off, never gone: it says why to a pointer and a screen reader (the title, which is its description) and to a tap (below).
            aria-disabled={Boolean(sendOff)}
            title={sendOff ?? undefined}
            onClick={() => (sendOff ? setSaidWhy(true) : actions.shareSet(set, name))}
          >
            <Icon name="send" size={15} />
          </button>
        )}
        <MoreMenu
          label={`More actions for ${title}`}
          items={[
            {
              icon: "send",
              title: "Send to ForeFlight",
              text: sendOff ?? undefined,
              onSelect: () => actions.shareSet?.(set, name),
              hidden: !actions.shareSet,
              disabled: Boolean(sendOff),
            },
            { icon: "route", title: "Sketch a route into this set", onSelect: () => actions.sketchInto?.(set), hidden: readOnly || !sketched || !actions.sketchInto || !(set.pack || canChooseForSketches) },
            { icon: "copy", title: set.pack ? "Save a copy to Library…" : "Save as…", onSelect: () => actions.saveAs(set) },
            { icon: "layers", title: "Add to Mission Pack…", onSelect: () => actions.addToPack?.(set, state.link), hidden: Boolean(set.pack) || !actions.addToPack || set.kind === "mission", disabled: !state.link, text: state.link ? undefined : "Save it first" },
            { divider: true },
            { icon: "x", title: set.pack ? "Close here" : "Close", text: set.pack ? "It stays in the pack" : "Takes these routes out of this session", onSelect: () => actions.close(set) },
          ]}
        />
      </div>
      {saidWhy && sendOff && (
        <div className="shell-set__why" role="status">
          <Icon name="alertTriangle" size={13} />
          <span>{sendOff}, so there is nothing to send.</span>
        </div>
      )}
      <div className="shell-card__chips" style={{ marginTop: 7 }}>
        {set.kind === "mission" && !set.pack && <Chip tone="info" icon="download">Imported</Chip>}
        {stateChip(set, state)}
        <span className="shell-card__meta">{setSummary(set.routes, set.flots)}</span>
      </div>
      {target && !readOnly && (
        <div className="shell-card__people" style={set.pack ? undefined : { color: "#9fb0bf" }}>
          <Icon name="route" size={13} color={set.pack ? "var(--pack)" : "#9fb0bf"} />
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
            disabled={clean || state.saving || empty}
            onClick={() => actions.save(set)}
          >
            <Icon name={clean ? "check" : "save"} size={15} />
            <span>{state.saving ? "Saving" : clean ? "Saved" : "Save"}</span>
          </button>
        )}
        {/* A FLOT is not written to AMPS: with no routes there is nothing to export. */}
        {set.routes.length > 0 && (
          <button type="button" className={`ui-btn${set.pack ? " ui-btn--grow" : ""}`} onClick={() => actions.export(set, { withThreats: withThreats && threatCount > 0 })}>
            <Icon name="upload" size={15} />
            <span>Export .msnx</span>
          </button>
        )}
      </div>
      {threatCount > 0 && set.routes.length > 0 && (
        <label className="shell-set__check">
          <input type="checkbox" checked={withThreats} onChange={(event) => setWithThreats(event.target.checked)} />
          Include threats ({threatCount}) as a .ths file
        </label>
      )}
      <div className="shell-set__divider" />
      {empty && (
        <div className="shell-set__empty">
          {readOnly ? "No routes in this set." : "No routes in this set yet. Sketch one, or move one here from another set."}
        </div>
      )}
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
            onColor={!readOnly && actions.recolor ? (id, color) => actions.recolor(set, id, color) : undefined}
            onCombine={!readOnly && sketched && actions.combineRoutes && set.routes.length > 1 ? (r) => onAsk({ type: "combine", setKey: set.key, routeId: r.id }) : undefined}
            onMove={!readOnly && sketched && actions.moveRoute ? (r) => onAsk({ type: "move", setKey: set.key, routeId: r.id }) : undefined}
          />
          {expanded.has(route.id) && (
            <RoutePlanSection route={route} readOnly={readOnly} localPointNames={localPointNames} {...pointSearch} {...plan(set)} />
          )}
        </React.Fragment>
      ))}
      {(set.flots ?? []).map((flot) => (
        <FlotRow key={flot.id} flot={flot} readOnly={readOnly} onFlip={actions.flipFlot} onRemove={actions.removeFlot} />
      ))}
    </section>
  );
};

/**
 * `sets`: [{ key, kind, fileName, routes, flots?, pack? }]; `stateOf(key)` from useRouteSaves (in a pack: a
 * stand-in with the name). `actions`: save, saveAs, rename, close, export, share (a route), shareSet
 * (`(set, name)`, the whole set), toggleVisibility, removeRoute, addToPack, flipFlot, removeFlot, and for
 * arranging routes: recolor `(set, routeId, color)`, combineRoutes `(set, firstId, secondId)`, moveRoute
 * `(set, routeId, { key } | { newName })`, newSet `()`, sketchInto `(set)`. A set of this session
 * with `target: true` is where new sketches go. `plan(set)`: the plan-editing calls for that set's routes. `pointSearch`: what the plan's add-a-point field searches.
 */
const RoutesDockPanel = ({
  sets,
  stateOf,
  threatCount = 0,
  actions,
  plan,
  localPointNames,
  pointSearch,
  sketch,
  flot = null,
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
  // What is being asked about a route: { type: "move" | "combine", setKey, routeId }. It is asked of the
  // route and set as they are now, and put away if either has gone (someone else's change in a pack).
  const [asking, setAsking] = useState(null);
  const askedSet = asking ? sets.find((set) => set.key === asking.setKey) : null;
  const askedRoute = askedSet?.routes.find((route) => route.id === asking.routeId) ?? null;
  const takesRoutes = (set) => set.kind !== "mission" && !set.pack?.finished;
  const sketchSetCount = sets.filter((set) => set.kind !== "mission").length;
  // The foot's buttons, two to a row once there are more than three of them (one left over takes its row).
  const twoToARow = actions.newSet ? { flexBasis: "calc(50% - 4px)" } : undefined;
  const suggestedSetName = () => {
    const taken = new Set(sets.map((set) => stateOf(set.key).name));
    let n = sets.length + 1;
    while (taken.has(`ROUTES ${n}`)) n += 1;
    return `ROUTES ${n}`;
  };

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
                  pointSearch={pointSearch}
                  canChooseForSketches={sketchSetCount > 1}
                  onAsk={setAsking}
                />
              ))}
            </div>
          </PanelSection>
        )}
      </div>
      {asking?.type === "move" && askedRoute && (
        <MoveRouteDialog
          route={askedRoute}
          fromName={stateOf(askedSet.key).name}
          destinations={sets.filter((set) => set.key !== askedSet.key && takesRoutes(set)).map((set) => ({ key: set.key, name: stateOf(set.key).name, routes: set.routes, flots: set.flots }))}
          suggestedName={suggestedSetName()}
          onCancel={() => setAsking(null)}
          onMove={(destination) => {
            setAsking(null);
            actions.moveRoute(askedSet, askedRoute.id, destination);
          }}
        />
      )}
      {asking?.type === "combine" && askedRoute && askedSet.routes.length > 1 && (
        <CombineRoutesDialog
          route={askedRoute}
          others={askedSet.routes.filter((route) => route.id !== askedRoute.id)}
          describe={routeMeta}
          onCancel={() => setAsking(null)}
          onCombine={(otherId) => {
            setAsking(null);
            actions.combineRoutes(askedSet, askedRoute.id, otherId);
          }}
        />
      )}
      {!readOnly && (
        <div className="shell-dock__foot" style={{ flexWrap: "wrap" }}>
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
              <button type="button" className="ui-btn ui-btn--38 ui-btn--grow" style={twoToARow} onClick={sketch.onStart} disabled={!sketch.enabled}>
                <Icon name="route" size={15} />
                <span>Sketch a route</span>
              </button>
              {!pack && (
                <button type="button" className="ui-btn ui-btn--38 ui-btn--grow" style={twoToARow} onClick={onImportMsnx} disabled={!canImportMsnx}>
                  <Icon name="download" size={15} />
                  <span>Import .msnx</span>
                </button>
              )}
              {actions.newSet && (
                <button type="button" className="ui-btn ui-btn--38 ui-btn--grow" style={twoToARow} onClick={() => actions.newSet(suggestedSetName())}>
                  <Icon name="plus" size={15} />
                  <span>New route set</span>
                </button>
              )}
              {flot && (
                <button
                  type="button"
                  className="ui-btn ui-btn--38 ui-btn--grow"
                  style={twoToARow ?? { flexBasis: "100%" }}
                  aria-pressed={flot.active}
                  onClick={flot.active ? flot.onCancel : flot.onStart}
                  disabled={!flot.enabled}
                >
                  <Icon name={flot.active ? "x" : "flag"} size={15} />
                  <span>{flot.active ? "Cancel FLOT" : "Draw FLOT"}</span>
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
