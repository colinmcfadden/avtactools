import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { shortName } from "../../ui/Avatar";
import { ConfirmDialog } from "../../ui/Dialog";
import Icon from "../../ui/Icon";
import NameDialog from "../../ui/NameDialog";
import { dateAtTime } from "../../ui/time";
import { copyFromLibrary, deleteItemOp, droppedVersions, myEditsName, saveVersionToLibrary } from "../packActions";
import * as packApi from "../packApi";
import { packDiagramId } from "../packLz";
import { packLocalRef } from "../packRef";
import { actorName, renameSummary, updateFromOriginalSummary } from "../packSentences";
import { useInviteLink } from "../useInviteLink";
import { useMissionPack } from "../useMissionPack";
import { usePackLz } from "../usePackLz";
import { usePackPoints } from "../usePackPoints";
import { usePackRoutes } from "../usePackRoutes";
import { changedSince, usePackSeen } from "../usePackSeen";
import MembersDialog from "./MembersDialog";
import NewPackDialog from "./NewPackDialog";
import PackDescriptionDialog from "./PackDescriptionDialog";
import { AddFromLibraryDialog, AddToPackDialog, kindWords } from "./PackDialogs";
import TeamsDialog from "./TeamsDialog";
import { usePacksHome } from "./usePacksHome";

/*
 * The web's side of an open Mission Pack, joined to the editors (docs/MENU_REDESIGN.md §8). One
 * workspace is open at a time: the Library, or a pack.
 *
 *  - Opening a pack parks the Library's open LZ/PZs (with their unsaved changes) and puts them back
 *    on the way out: switching never discards anything. The Library's routes, points and missions
 *    stay loaded but are not shown while a pack is open.
 *  - In a pack, the pack's route sets and point sets are all on the map, and its first LZ/PZ is
 *    opened; anything created while it is open goes into it.
 *  - "Who is here" is only the people who have this pack open (the live service's presence), never
 *    whether someone is signed in somewhere. Each person's pointer is shared as they move it over the
 *    map, so the others see where they are looking.
 *
 * Returns what App needs: the pack's state, the actions behind the switcher, the pack panel and the
 * cards, and `dialogs` to render.
 */

const STORAGE_KEY = "ezpz.openPack";
const readStored = () => {
  try {
    return window.localStorage.getItem(STORAGE_KEY) || null;
  } catch {
    return null;
  }
};
const store = (uuid) => {
  try {
    if (uuid) window.localStorage.setItem(STORAGE_KEY, uuid);
    else window.localStorage.removeItem(STORAGE_KEY);
  } catch {
    // Not remembered across reloads; nothing else depends on it.
  }
};

const EMPTY_WORKSPACE = { diagramOrder: [], diagramsById: {}, activeDiagramId: null };

const REFUSED_WORDS = {
  read_only: "you can only look at this pack, so changes are not saved to it",
  gone: "the pack is no longer available",
  loading: "the pack is still opening",
  closed: "no pack is open",
  empty_point_set: "a point set needs at least one point",
};

// Why edits still waiting when a pack was closed were never taken (packClient's drain).
const LOST_WORDS = {
  pack_finished: "the pack was finished",
  read_only: "you can only look at it now",
  gone: "it is no longer available to you",
  signed_out: "you signed out before they were sent",
};

/**
 * The pack the top bar names: the open one, or while it is opening or cannot be reached (no `meta`
 * yet), its line in the switcher's list, so the bar never says "Library" while the Library is parked.
 */
export const topBarPack = (open, meta, packs = []) => {
  if (!open) return null;
  if (meta) return { name: meta.name, status: meta.status };
  const listed = packs.find((pack) => pack.uuid === open);
  return { name: listed?.name ?? "Mission Pack", status: listed?.status };
};

/**
 * How many people can open the pack: "18 members" in the switcher, "18 members can see it" on a card.
 * Its own members are known live, from the session. Those it reaches through a shared team only the
 * server can count (someone joining the team changes it with no event in the pack), so with a team it
 * is the switcher's line for the pack (`listed`, asked again whenever the members or the team change),
 * else the count the pack came with; never fewer than its own members.
 */
export const packAudience = (meta, members, listed) => {
  if (!meta) return null;
  const own = members?.length ?? meta.member_count ?? 1;
  if (!meta.team) return own;
  return Math.max(own, listed?.audience_count ?? meta.audience_count ?? own);
};

// The last edit an update would replace, after the count: "…, including Jess R.’s last, “set the
// landing heading of LZ HAWK to 270°”." The sentence was written with the editor's name in front.
const lastChangeWords = (count, last, me) => {
  const summary = (last?.summary ?? "").trim().replace(/\.$/, "");
  if (count === 1) return summary ? `: ${summary}.` : ".";
  if (!last?.actor) return ".";
  const name = last.actor.name ?? "";
  const whose = me != null && last.actor.id === me ? "your" : `${shortName(name)}’s`;
  const what = name && summary.startsWith(`${name} `) ? summary.slice(name.length + 1) : summary;
  return what ? `, including ${whose} last, “${what}”.` : `, including ${whose} last.`;
};

/**
 * Update a copy from its Library original (mockup 17-B): when the original changed, and what in the
 * pack the update replaces. Those are asked of the server as the dialog opens, because the item as the
 * pack was loaded does not follow edits made since; a sentence whose facts did not come back is left
 * out, and only the plain warning stands in for the count.
 */
export const UpdateFromOriginalDialog = ({ packUuid, item, me, getItem = packApi.getItem, onCancel, onConfirm }) => {
  const [fresh, setFresh] = useState({ status: "loading", source: null });
  useEffect(() => {
    let current = true;
    getItem(packUuid, item.uuid).then(
      (answer) => current && setFresh({ status: "done", source: answer?.item?.source ?? null }),
      () => current && setFresh({ status: "failed", source: null }),
    );
    return () => {
      current = false;
    };
  }, [getItem, packUuid, item.uuid]);
  const { status, source } = fresh;
  const changedAt = source?.original_updated_at ? dateAtTime(source.original_updated_at) : "";
  const count = Number.isInteger(source?.pack_changes) ? source.pack_changes : null;
  let warning = null;
  if (count > 0) {
    warning = (
      <>
        <b>{count} {count === 1 ? "change" : "changes"} made in the pack will be replaced</b>
        {lastChangeWords(count, source.last_pack_change, me)} The history keeps what was there.
      </>
    );
  } else if (count === null && status !== "loading") {
    warning = "Changes made to it in the pack will be replaced. The history keeps what was there.";
  }
  return (
    <ConfirmDialog
      title={`Update “${item.name}” from Library?`}
      text={changedAt
        ? `Your Library version changed ${changedAt}. Updating replaces the pack copy with it.`
        : "Updating replaces the pack copy with your Library version."}
      icon="refresh"
      iconTone="warn"
      confirmLabel="Update from original"
      confirmIcon="refresh"
      onCancel={onCancel}
      onConfirm={onConfirm}
    >
      {warning && (
        <div className="ui-callout" style={{ marginTop: 12 }}>
          <Icon name="alertTriangle" size={16} />
          <span>{warning}</span>
        </div>
      )}
    </ConfirmDialog>
  );
};

export const usePackWorkspace = ({
  enabled,
  user,
  toast,
  dock,
  lz,
  sketch,
  points,
  library,
  onOpenLibrary,
}) => {
  const me = user?.id ?? null;
  const actor = actorName(user);
  const home = usePacksHome({ enabled });
  const [packUuid, setPackUuid] = useState(() => (enabled ? readStored() : null));
  const open = enabled ? packUuid : null;
  // A pack closed with edits still waiting sends them on; any it would not take by then are said here,
  // as its panel has gone.
  const onLost = useCallback(
    ({ pack, lost }) => {
      const what = lost.length === 1 ? "A change" : `${lost.length} changes`;
      const why = LOST_WORDS[lost[0]?.reason] ?? "the pack would not take them";
      toast({ tone: "warn", message: `${what} to ${pack?.name ?? "the pack"} could not be saved: ${why}.` });
    },
    [toast],
  );
  const live = useMissionPack(open, me, { onLost });
  // Just after a switch, the hook still holds the previous pack's client for a render: its session
  // and people belong to that pack, not this one, and must not be taken for this one's.
  const fresh = Boolean(live.session) && live.session.pack?.uuid === open;
  const session = fresh ? live.session : null;
  const liveStatus = fresh || !live.session ? live.status : "loading";
  const meta = session?.pack ?? null;
  const readOnly = Boolean(session?.readOnly);
  const items = useMemo(() => (fresh ? live.items : []), [fresh, live.items]);
  const people = fresh ? live.people : null;
  const others = useMemo(() => (people ?? []).filter((p) => p.user_id !== me), [people, me]);
  const [dialog, setDialog] = useState(null);
  const [panelTab, setPanelTab] = useState("items");
  const closedHere = useRef(new Set());
  // The pack this person is leaving or deleting, whose going is theirs and not news to them.
  const leaving = useRef(null);
  const openNow = useRef(open);
  openNow.current = open;

  const reportGone = useCallback((localId, name) => toast({ tone: "info", message: `“${name}” was removed from the pack.` }), [toast]);
  const reportRefused = useCallback(
    (why) => toast({ tone: "warn", message: `That change was not saved to the pack: ${REFUSED_WORDS[why] ?? packApi.packErrorMessage(why)}.` }),
    [toast],
  );

  const packLz = usePackLz({
    packUuid: open,
    items,
    edit: live.edit,
    readOnly,
    workspace: lz.workspace,
    importDiagram: lz.importDiagram,
    applyRemoteDiagram: lz.applyRemoteDiagram,
    removeDiagram: lz.removeDiagram,
    setActiveDiagram: lz.setActiveDiagram,
    actor,
    onGone: reportGone,
    onRefused: reportRefused,
  });
  const packRoutes = usePackRoutes({
    packUuid: open,
    items,
    edit: live.edit,
    readOnly,
    actor,
    sketchedRoutes: sketch.sketchedRoutes,
    loadSketchRoutes: sketch.loadSketchRoutes,
    replaceRouteSet: sketch.replaceRouteSet,
    removeRouteSet: sketch.removeRouteSet,
    onGone: reportGone,
    onRefused: reportRefused,
  });
  const packPoints = usePackPoints({
    packUuid: open,
    items,
    edit: live.edit,
    readOnly,
    actor,
    pointSets: points.pointSets,
    setPointSets: points.setPointSets,
    onGone: reportGone,
    onRefused: reportRefused,
  });

  // -- Switching workspace ------------------------------------------------------------------

  const parked = useRef(null);
  const workspaceRef = useRef(lz.workspace);
  workspaceRef.current = lz.workspace;

  const park = useCallback(() => {
    if (parked.current) return;
    parked.current = workspaceRef.current;
    lz.hydrateWorkspace(EMPTY_WORKSPACE);
  }, [lz]);

  const unpark = useCallback(() => {
    if (!parked.current) return;
    lz.hydrateWorkspace(parked.current);
    parked.current = null;
  }, [lz]);

  // Takes what one pack put in the editors out of them (its changes still waiting are sent first).
  const closeLocal = useCallback(
    (uuid) => {
      packLz.flush();
      packRoutes.flush();
      packPoints.flush();
      packRoutes.open.forEach((itemUuid) => packRoutes.closeItem(itemUuid));
      points.setPointSets((sets) => sets.filter((set) => packLocalRef(set.id)?.pack !== uuid));
    },
    [packLz, packRoutes, packPoints, points],
  );

  const openPack = useCallback(
    (next) => {
      const target = next || null;
      if (target === open) {
        if (target) dock.show("pack");
        return;
      }
      if (open) closeLocal(open);
      if (target) park();
      else unpark();
      if (target && open) lz.hydrateWorkspace(EMPTY_WORKSPACE);
      closedHere.current = new Set();
      if (target) leaving.current = null;
      setPackUuid(target);
      store(target);
      setPanelTab("items");
      dock.show(target ? "pack" : "lz");
    },
    [open, closeLocal, park, unpark, lz, dock],
  );

  // A pack remembered from last time: park the (empty) Library session for it.
  useEffect(() => {
    if (open && !parked.current) park();
    // Once, at start.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // A pack that has gone (deleted, or no longer this person's to see): back to the Library. One this
  // person is leaving or deleting goes too, and is not news to them.
  useEffect(() => {
    if (liveStatus !== "gone" || !open) return;
    if (leaving.current !== open) toast({ tone: "warn", message: `${meta?.name ?? "That pack"} is no longer available to you.` });
    home.refresh();
    openPack(null);
  }, [liveStatus, open, meta?.name, toast, home, openPack]);

  // Once a pack has loaded, its route sets and point sets go on the map (and new ones as they are
  // added), and its first LZ/PZ is opened.
  const firstOpened = useRef(null);
  useEffect(() => {
    if (!open || !session) return;
    items.forEach((item) => {
      if (closedHere.current.has(item.uuid)) return;
      if (item.kind === "route" && !packRoutes.open.includes(item.uuid)) packRoutes.openItem(item.uuid);
      if (item.kind === "pointset" && !points.pointSets.some((set) => set.id === `pack:${open}:${item.uuid}`)) packPoints.openItem(item.uuid);
    });
    if (firstOpened.current !== open) {
      firstOpened.current = open;
      const first = items.find((item) => item.kind === "lz");
      if (first && !Object.keys(workspaceRef.current?.diagramsById ?? {}).length) packLz.openItem(first.uuid);
    }
  }, [open, session, items, packRoutes, packPoints, packLz, points.pointSets]);

  // -- Presence and how far this person has looked ---------------------------------------------

  const activeItem = packLocalRef(lz.workspace?.activeDiagramId)?.pack === open ? packLocalRef(lz.workspace.activeDiagramId).item : null;
  const focusItem = useRef(activeItem);
  focusItem.current = activeItem;
  const { setFocus } = live;
  useEffect(() => {
    if (open) setFocus?.(activeItem ? { item: activeItem } : {});
  }, [open, activeItem, setFocus]);

  /** Where this person is pointing on the map (null when the pointer leaves it). */
  const pointAt = useCallback(
    (latlng) => {
      if (!open) return;
      const at = latlng ? [Math.round(latlng.lat * 1e5) / 1e5, Math.round(latlng.lng * 1e5) / 1e5] : undefined;
      setFocus?.({ ...(focusItem.current ? { item: focusItem.current } : {}), ...(at ? { at } : {}) });
    },
    [open, setFocus],
  );

  const [visible, setVisible] = useState(() => typeof document === "undefined" || !document.hidden);
  useEffect(() => {
    const change = () => setVisible(!document.hidden);
    document.addEventListener("visibilitychange", change);
    return () => document.removeEventListener("visibilitychange", change);
  }, []);
  // Opening a pack shows its panel at once, before the pack (and how far they had looked) has loaded.
  const seen = usePackSeen({
    packUuid: open,
    headSeq: session?.seq ?? 0,
    seenSeq: meta?.seen_seq ?? 0,
    loaded: Boolean(session),
    looking: Boolean(session) && dock.panel === "pack" && !dock.collapsed && visible,
  });
  const newCount = items.filter((item) => changedSince(item, seen.newSince, me)).length;

  // -- Invitations -------------------------------------------------------------------------------

  const invite = useInviteLink({
    enabled: Boolean(enabled && user),
    onJoined: (answer) => {
      home.refresh();
      if (answer?.pack?.uuid) openPack(answer.pack.uuid);
    },
  });
  useEffect(() => {
    if (invite.status === "joined" && invite.message) toast({ tone: "pack", message: invite.message });
    if (invite.status === "failed" && invite.message) {
      toast({ tone: "error", message: invite.message, action: invite.retryable ? { label: "Try again", onClick: invite.retry } : undefined });
    }
    // Once per outcome.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [invite.status]);

  const answerInvite = useCallback(
    async (inv, accept) => {
      try {
        const answer = accept ? await packApi.acceptInvite(inv.id) : await packApi.declineInvite(inv.id);
        await home.refresh();
        if (accept) {
          toast({ tone: "pack", message: answer?.pack ? `You joined ${answer.pack.name}.` : answer?.team ? `You joined the team ${answer.team.name}.` : "Invitation accepted." });
          if (answer?.pack?.uuid) openPack(answer.pack.uuid);
        }
      } catch (err) {
        toast({ tone: "error", message: packApi.packErrorMessage(err) });
        home.refresh();
      }
    },
    [home, toast, openPack],
  );

  // -- Items ---------------------------------------------------------------------------------------

  const openIds = useMemo(() => {
    const ids = new Set(packRoutes.open);
    if (open) {
      (lz.workspace?.diagramOrder ?? []).forEach((id) => {
        const ref = packLocalRef(id);
        if (ref?.pack === open) ids.add(ref.item);
      });
      points.pointSets.forEach((set) => {
        const ref = packLocalRef(set.id);
        if (ref?.pack === open) ids.add(ref.item);
      });
    }
    return ids;
  }, [open, packRoutes.open, lz.workspace, points.pointSets]);

  const openItem = useCallback(
    (item) => {
      closedHere.current.delete(item.uuid);
      if (item.kind === "lz") {
        packLz.openItem(item.uuid);
        dock.show("lz");
      } else if (item.kind === "route") {
        packRoutes.openItem(item.uuid);
        dock.show("routes");
      } else {
        packPoints.openItem(item.uuid);
        dock.show("imports");
      }
    },
    [packLz, packRoutes, packPoints, dock],
  );

  const closeItem = useCallback(
    (item) => {
      closedHere.current.add(item.uuid);
      if (item.kind === "lz") lz.removeDiagram(packDiagramId(open, item.uuid));
      else if (item.kind === "route") packRoutes.closeItem(item.uuid);
      else packPoints.closeItem(item.uuid);
    },
    [lz, open, packRoutes, packPoints],
  );

  const refreshAll = useCallback(() => {
    live.refresh?.();
    home.refresh();
  }, [live, home]);

  const run = useCallback(
    async (work, done) => {
      try {
        const answer = await work();
        if (done) toast(typeof done === "function" ? done(answer) : done);
        refreshAll();
        return answer;
      } catch (err) {
        toast({ tone: "error", message: packApi.packErrorMessage(err) });
        return null;
      }
    },
    [toast, refreshAll],
  );

  const actions = useMemo(
    () => ({
      open: openItem,
      close: closeItem,
      rename: (item) => setDialog({ kind: "rename", item }),
      saveCopy: (item) => setDialog({ kind: "saveCopy", item }),
      updateFromOriginal: (item) => setDialog({ kind: "update", item }),
      remove: (item) => setDialog({ kind: "remove", item }),
      invite: () => setDialog({ kind: "members", focusInvite: true }),
      members: () => setDialog({ kind: "members" }),
      addFromLibrary: () => {
        library.refreshAll?.();
        setDialog({ kind: "addFromLibrary" });
      },
      finish: () => setDialog({ kind: "finish" }),
      reopen: () => setDialog({ kind: "reopen" }),
      duplicate: () => setDialog({ kind: "duplicate" }),
      renamePack: () => setDialog({ kind: "renamePack" }),
      describePack: () => setDialog({ kind: "describePack" }),
      leavePack: () => setDialog({ kind: "leavePack" }),
      deletePack: () => setDialog({ kind: "deletePack" }),
    }),
    [openItem, closeItem, library],
  );

  // Leaving or deleting the open pack: back to the Library, and the switcher asked again so it is no
  // longer listed. A refusal (an owner must hand the pack over first) leaves everything as it was.
  const goOut = useCallback(
    async (work, done) => {
      const uuid = open;
      leaving.current = uuid;
      try {
        await work(uuid);
      } catch (err) {
        leaving.current = null;
        toast({ tone: "error", message: packApi.packErrorMessage(err) });
        return;
      }
      home.refresh();
      // Unless another pack was opened while the answer came.
      if (openNow.current === uuid) openPack(null);
      toast(done);
    },
    [open, home, openPack, toast],
  );

  // -- The edits a pack would not take ---------------------------------------------------------------

  const [keptDropped, setKeptDropped] = useState(0);
  const dropped = session?.dropped?.length ?? 0;
  const keepDropped = useCallback(async () => {
    const versions = droppedVersions(session);
    let saved = 0;
    for (const version of versions) {
      try {
        await saveVersionToLibrary({ kind: version.kind, name: myEditsName(version.name), data: version.data });
        saved += 1;
      } catch {
        // Counted below.
      }
    }
    setKeptDropped(dropped);
    library.refreshAll?.();
    toast(
      saved === versions.length
        ? { message: `Saved your version of ${saved === 1 ? `“${versions[0].name}”` : `${saved} items`} to your Library`, action: { label: "Open Library", onClick: onOpenLibrary } }
        : { tone: "error", message: `Saved ${saved} of ${versions.length}. Check the connection and try again.` },
    );
  }, [session, dropped, library, toast, onOpenLibrary]);

  // -- Who can open it ---------------------------------------------------------------------------------------

  // The switcher's line for the open pack is asked again when who can open it may have changed: its
  // members, or the team it is shared with (packAudience reads the team's count from it).
  const members = session?.members;
  const audienceKey = meta ? `${meta.team?.id ?? ""}:${(members ?? []).map((m) => m.user_id).join(",")}` : null;
  const shared = Boolean(meta?.team);
  const lastAudienceKey = useRef(null);
  const { refresh: refreshHome } = home;
  useEffect(() => {
    if (!audienceKey) return;
    const previous = lastAudienceKey.current;
    lastAudienceKey.current = { pack: open, key: audienceKey };
    if (shared && previous?.pack === open && previous.key !== audienceKey) refreshHome();
  }, [open, audienceKey, shared, refreshHome]);
  const audience = packAudience(meta, members, home.packs.find((p) => p.uuid === open));

  // -- Dialogs ---------------------------------------------------------------------------------------------

  const close = () => setDialog(null);
  let dialogs = null;
  const item = dialog?.item;
  if (dialog?.kind === "newPack") {
    dialogs = (
      <NewPackDialog
        teams={home.teams}
        onCancel={close}
        onCreated={(pack, { startFromLibrary, failed }) => {
          close();
          home.refresh();
          openPack(pack.uuid);
          toast({ tone: "pack", message: `Made ${pack.name}. You are its owner.` });
          if (failed.length) toast({ tone: "warn", message: `Could not add ${failed.join(", ")}. Try again from Members.` });
          if (startFromLibrary) {
            library.refreshAll?.();
            setDialog({ kind: "addFromLibrary" });
          }
        }}
      />
    );
  } else if (dialog?.kind === "members" && meta) {
    dialogs = (
      <MembersDialog
        pack={meta}
        members={session?.members ?? []}
        teams={home.teams}
        here={others}
        me={me}
        focusInvite={Boolean(dialog.focusInvite)}
        onChanged={refreshAll}
        onClose={close}
      />
    );
  } else if (dialog?.kind === "teams") {
    dialogs = <TeamsDialog teams={home.teams} me={me} onChanged={home.refresh} onClose={close} />;
  } else if (dialog?.kind === "addToPack") {
    dialogs = (
      <AddToPackDialog
        itemName={dialog.entry.name}
        packs={home.packs}
        onCancel={close}
        onAdd={async (target) => {
          await copyFromLibrary(target.uuid, { kind: dialog.kind2, record: dialog.entry, actor });
          close();
          refreshAll();
          toast({
            tone: "pack",
            message: `Added a copy of “${dialog.entry.name}” to ${target.name}`,
            action: target.uuid !== open ? { label: "Open pack", onClick: () => openPack(target.uuid) } : undefined,
          });
        }}
      />
    );
  } else if (dialog?.kind === "addFromLibrary" && meta) {
    dialogs = (
      <AddFromLibraryDialog
        packName={meta.name}
        library={{ lz: library.history ?? [], route: library.savedRoutes ?? [], pointset: library.savedPointSets ?? [] }}
        onCancel={close}
        onAdd={async (chosen) => {
          let added = 0;
          for (const { kind, entry } of chosen) {
            try {
              await copyFromLibrary(open, { kind, record: entry, actor });
              added += 1;
            } catch (err) {
              toast({ tone: "error", message: `“${entry.name}”: ${packApi.packErrorMessage(err)}` });
            }
          }
          close();
          live.refresh?.();
          if (added) toast({ tone: "pack", message: `Added ${added === 1 ? "a copy" : `${added} copies`} to ${meta.name}` });
        }}
      />
    );
  } else if (dialog?.kind === "finish" && meta) {
    dialogs = (
      <ConfirmDialog
        title={`Finish ${meta.name}?`}
        text="Everyone becomes read-only, you included. Members can still view, export and save copies to their own Library. You can reopen the pack whenever you need to."
        icon="flag"
        iconTone="pack"
        confirmLabel="Finish pack"
        confirmTone="pack"
        onCancel={close}
        onConfirm={() => {
          close();
          run(() => packApi.finishPack(open), { tone: "pack", message: `${meta.name} is finished and read-only for everyone.` });
        }}
      />
    );
  } else if (dialog?.kind === "reopen" && meta) {
    dialogs = (
      <ConfirmDialog
        title={`Reopen ${meta.name}?`}
        text="Editors can change it again. The history keeps when it was finished."
        icon="refresh"
        iconTone="pack"
        confirmLabel="Reopen pack"
        confirmTone="pack"
        onCancel={close}
        onConfirm={() => {
          close();
          run(() => packApi.reopenPack(open), { tone: "pack", message: `${meta.name} is open for editing again.` });
        }}
      />
    );
  } else if (dialog?.kind === "duplicate" && meta) {
    dialogs = (
      <NameDialog
        title="Duplicate as a new pack"
        subtitle={`A new pack with copies of everything in ${meta.name}. You are its owner; nobody else is in it yet.`}
        icon="layers"
        initialName={`${meta.name} 2`.slice(0, 100)}
        confirmLabel="Duplicate"
        upperCase
        onCancel={close}
        onConfirm={async (name) => {
          const made = await packApi.duplicatePack(open, name);
          close();
          home.refresh();
          if (made?.uuid) openPack(made.uuid);
          toast({ tone: "pack", message: `Made ${made?.name ?? name}.` });
        }}
      />
    );
  } else if (dialog?.kind === "renamePack" && meta) {
    dialogs = (
      <NameDialog
        title={`Rename ${meta.name}`}
        subtitle="Everyone in the pack sees the new name."
        initialName={meta.name}
        confirmLabel="Rename"
        upperCase
        onCancel={close}
        onConfirm={async (name) => {
          if (name !== meta.name) {
            try {
              await packApi.updatePack(open, { name });
            } catch (err) {
              throw new Error(packApi.packErrorMessage(err));
            }
            refreshAll();
            toast({ tone: "pack", message: `Renamed ${meta.name} to ${name}.` });
          }
          close();
        }}
      />
    );
  } else if (dialog?.kind === "describePack" && meta) {
    dialogs = (
      <PackDescriptionDialog
        packName={meta.name}
        initial={meta.description ?? ""}
        onCancel={close}
        onSave={async (description) => {
          if (description !== (meta.description ?? "")) {
            try {
              await packApi.updatePack(open, { description });
            } catch (err) {
              throw new Error(packApi.packErrorMessage(err));
            }
            refreshAll();
            toast({ tone: "pack", message: description ? `Saved the description of ${meta.name}.` : `Took the description off ${meta.name}.` });
          }
          close();
        }}
      />
    );
  } else if (dialog?.kind === "leavePack" && meta && meta.role === "owner") {
    // The server's rule (owner_must_transfer): a pack always has an owner.
    dialogs = (
      <ConfirmDialog
        title={`Hand ${meta.name} over first`}
        text="A pack always has an owner. In Members, choose Owner beside someone's name; you then become an editor and can leave."
        icon="users"
        iconTone="pack"
        confirmLabel="Open Members"
        confirmIcon="users"
        onCancel={close}
        onConfirm={() => setDialog({ kind: "members" })}
      />
    );
  } else if (dialog?.kind === "leavePack" && meta && !(session?.members ?? []).some((m) => m.user_id === me)) {
    // In it only through the team it is shared with: there is no membership to end.
    const team = (meta.team && home.teams.find((t) => t.id === meta.team.id)) || meta.team;
    dialogs = (
      <ConfirmDialog
        title={`You have ${meta.name} through ${team?.name || "a team"}`}
        text="It is shared with the team, so it opens for everyone in it. Its owner can stop sharing it, or you can leave the team under Manage teams."
        icon="users"
        iconTone="pack"
        cancelLabel="Close"
        confirmLabel="Manage teams"
        onCancel={close}
        onConfirm={() => {
          home.refresh();
          setDialog({ kind: "teams" });
        }}
      />
    );
  } else if (dialog?.kind === "leavePack" && meta) {
    // Someone in the team the pack is shared with keeps it through the team after leaving.
    const viaTeam = meta.team ? home.teams.find((t) => t.id === meta.team.id) : null;
    dialogs = (
      <ConfirmDialog
        title={`Leave ${meta.name}?`}
        text={[
          viaTeam
            ? `You can still open it through ${viaTeam.name}, which it is shared with, as ${meta.team.role === "viewer" ? "a viewer" : "an editor"}.`
            : "It stops opening for you, and only its owner can add you again.",
          "What you added stays in the pack.",
        ]}
        icon="logOut"
        iconTone="danger"
        confirmLabel="Leave pack"
        confirmTone="danger"
        onCancel={close}
        onConfirm={() => {
          close();
          goOut((uuid) => packApi.removeMember(uuid, me), { message: `You left ${meta.name}.` });
        }}
      />
    );
  } else if (dialog?.kind === "deletePack" && meta) {
    dialogs = (
      <ConfirmDialog
        title={`Delete ${meta.name}?`}
        text={[
          audience > 1
            ? `It is deleted for all ${audience} people who can open it, with every item in it and its history.`
            : "It is deleted with every item in it and its history.",
          "This cannot be undone. Anyone who wants to keep an item can save a copy to their Library first.",
        ]}
        icon="trash"
        iconTone="danger"
        confirmLabel="Delete pack"
        confirmTone="danger"
        onCancel={close}
        onConfirm={() => {
          close();
          goOut((uuid) => packApi.deletePack(uuid), { message: `Deleted ${meta.name}.` });
        }}
      />
    );
  } else if (dialog?.kind === "rename" && item) {
    dialogs = (
      <NameDialog
        title={`Rename “${item.name}”`}
        subtitle="Everyone in the pack sees the new name."
        initialName={item.name}
        confirmLabel="Rename"
        upperCase
        onCancel={close}
        onConfirm={(name) => {
          if (name !== item.name) {
            const refused = live.edit([{ type: "item.rename", item: item.uuid, name, summary: renameSummary(actor, item.name, name) }]);
            if (refused) reportRefused(refused);
          }
          close();
        }}
      />
    );
  } else if (dialog?.kind === "saveCopy" && item) {
    dialogs = (
      <NameDialog
        title="Save a copy to your Library"
        subtitle={`From ${meta?.name ?? "the pack"}.`}
        icon="copy"
        initialName={item.name}
        confirmLabel="Save copy"
        hint="Only you can see this copy. Later changes in the pack do not update it."
        onCancel={close}
        onConfirm={async (name) => {
          await packApi.saveItemToLibrary(open, item.uuid, name);
          close();
          library.refreshAll?.();
          toast({ message: `Saved “${name}” to your Library`, action: { label: "Open Library", onClick: onOpenLibrary } });
        }}
      />
    );
  } else if (dialog?.kind === "update" && item) {
    dialogs = (
      <UpdateFromOriginalDialog
        packUuid={open}
        item={item}
        me={me}
        onCancel={close}
        onConfirm={() => {
          close();
          run(() => packApi.updateFromOriginal(open, item.uuid, updateFromOriginalSummary(actor, item.name)), { message: `Updated “${item.name}” from your Library` });
        }}
      />
    );
  } else if (dialog?.kind === "remove" && item) {
    dialogs = (
      <ConfirmDialog
        title={`Remove “${item.name}” from ${meta?.name ?? "the pack"}?`}
        text={`The ${kindWords(item.kind)} goes for everyone in the pack. The history keeps what it was, and anyone can save a copy of it first.`}
        icon="trash"
        iconTone="danger"
        confirmLabel="Remove"
        confirmTone="danger"
        onCancel={close}
        onConfirm={() => {
          close();
          const refused = live.edit([deleteItemOp({ uuid: item.uuid, name: item.name, actor })]);
          if (refused) reportRefused(refused);
        }}
      />
    );
  }

  // -- What the cards and the top bar show ------------------------------------------------------------------

  const byUuid = useMemo(() => new Map(items.map((i) => [i.uuid, i])), [items]);
  const itemOf = useCallback((localId) => {
    const ref = packLocalRef(localId);
    return ref && ref.pack === open ? byUuid.get(ref.item) ?? null : null;
  }, [byUuid, open]);
  const itemInfo = useCallback(
    (localId) => {
      const found = itemOf(localId);
      if (!found) return {};
      return {
        fromLibrary: Boolean(found.source),
        originalChanged: found.source?.original === "changed",
        waiting: Boolean(found.pendingCreate) || (session?.pending ?? []).some((p) => p.op?.item === found.uuid),
      };
    },
    [itemOf, session],
  );
  const presenceOn = useCallback(
    (localId) => {
      const ref = packLocalRef(localId);
      if (!ref || ref.pack !== open) return [];
      return others.filter((p) => p.focus?.item === ref.item).map((p) => ({ id: p.user_id, name: p.name }));
    },
    [others, open],
  );

  const pending = session?.pending?.length ?? 0;
  const topStatus = !open
    ? null
    : liveStatus === "loading"
      ? "loading"
      : liveStatus === "error"
        ? "error"
        : meta?.status === "finished"
          ? "finished"
          : pending > 0
            ? typeof navigator !== "undefined" && navigator.onLine === false
              ? "waiting"
              : "sending"
            : liveStatus === "polling"
              ? "polling"
              : "live";

  const packForPanels = meta
    ? {
        uuid: meta.uuid,
        name: meta.name,
        status: meta.status,
        role: meta.role,
        // Everyone who can open it, its shared team included ("N members can see it").
        memberCount: audience,
        finished: meta.status === "finished",
        // Finished, or a viewer: either way the pack takes no change from this person.
        readOnly,
        finishedBy: meta.finished_by?.name,
        finishedAt: meta.finished_at,
      }
    : null;

  /** Copies a Library record into the open pack (the Library's "Add to pack"). */
  const copyIn = useCallback(
    async (kind, entry) => {
      try {
        await copyFromLibrary(open, { kind, record: entry, actor });
      } catch (err) {
        throw new Error(packApi.packErrorMessage(err));
      }
      live.refresh?.();
      toast({ tone: "pack", message: `Added a copy of “${entry.name}” to ${meta?.name ?? "the pack"}` });
    },
    [open, actor, live, toast, meta],
  );

  // The Library's LZ/PZs parked while a pack is open, and how many have unsaved changes.
  const parkedUnsaved = parked.current
    ? (parked.current.diagramOrder ?? []).filter((id) => {
        const d = parked.current.diagramsById?.[id];
        return d && d.status === "analyzed" && (d.savedId == null || d.dirty);
      }).length
    : 0;

  const topPack = topBarPack(open, meta, home.packs);

  return {
    enabled,
    open,
    copyIn,
    parkedUnsaved,
    meta,
    status: liveStatus,
    session,
    items,
    readOnly,
    role: meta?.role ?? null,
    others,
    home,
    seen,
    newCount,
    dropped: Math.max(0, dropped - keptDropped),
    keepDropped,
    panelTab,
    setPanelTab,
    openIds,
    actions,
    openPack,
    answerInvite,
    newPack: () => setDialog({ kind: "newPack" }),
    manageTeams: () => {
      home.refresh();
      setDialog({ kind: "teams" });
    },
    addToPack: (kind, entry) => {
      home.refresh();
      setDialog({ kind: "addToPack", kind2: kind, entry });
    },
    packLz,
    packRoutes,
    packPoints,
    pointAt,
    itemInfo,
    presenceOn,
    topStatus,
    topPack,
    packForPanels,
    dialogs,
  };
};

export default usePackWorkspace;
