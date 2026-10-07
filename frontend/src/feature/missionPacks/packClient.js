import {
  abandon,
  batchAnswered,
  batchFailed,
  edit as editSession,
  nextBatch,
  openSession,
  receive,
  reloadSession,
  visibleItems,
} from "./packSession";
import { failureOf } from "./packApi";

/*
 * Keeps one open mission pack in step with the server: loads it, sends the edits
 * made here in order, and takes everyone else's from the live stream, or by asking
 * every few seconds where there is none (the Fly backend) or while it is down.
 * The state lives in packSession.js; this is only the plumbing, with the network,
 * the socket and the clock passed in so tests can drive them. useMissionPack is
 * the React side. See docs/MISSION_PACKS.md, §5.
 *
 * `status` is "loading", "live" (the stream is open), "polling", "gone" (deleted,
 * or no longer ours to see) or "error" (it could not be loaded: tried again, less
 * often the longer it fails, and at once on `wake()`).
 *
 * Stopping (the person switched workspace, or the pack closed) drains: the edits
 * still waiting are sent, and retried, with nothing more asked or shown. Any the
 * pack would not take by then are passed to `onLost`, as nothing else shows them.
 */

const POLL_MS = 3000;
const PRESENCE_MS = 100;
const RETRY_MS = [1000, 2000, 5000, 10000, 30000];

const defaultTimers = {
  setTimeout: (fn, ms) => setTimeout(fn, ms),
  clearTimeout: (id) => clearTimeout(id),
};

const newOpId = () =>
  (typeof crypto !== "undefined" && crypto.randomUUID ? crypto.randomUUID() : `op-${Date.now()}-${Math.random().toString(16).slice(2)}`);

export const createPackClient = ({
  packUuid,
  me,
  api,
  openSocket,
  getToken,
  timers = defaultTimers,
  newId = newOpId,
  isHidden = () => typeof document !== "undefined" && document.hidden,
  onLost = null,
}) => {
  let state = { status: "loading", session: null, items: [], people: [], error: null };
  const listeners = new Set();
  let stopped = false;
  // Set by stop(): the token then and how many edits had been dropped, while what waits is still sent.
  let closing = null;
  const active = () => !stopped && !closing;
  let socket = null;
  let welcomed = false;
  let socketRetry = 0;
  let sendRetry = 0;
  let loadRetry = 0;
  let loading = false;
  let sending = false;
  let catchingUp = false;
  let catchUpAgain = false;
  const timer = { poll: null, reconnect: null, send: null, presence: null, load: null };
  let focus = null;
  let focusSent = true;

  const publish = (changes) => {
    state = { ...state, ...changes };
    if (changes.session) state.items = visibleItems(changes.session);
    if (!closing) listeners.forEach((listener) => listener());
  };

  const clear = (name) => {
    if (timer[name] !== null) timers.clearTimeout(timer[name]);
    timer[name] = null;
  };

  const later = (name, fn, ms) => {
    clear(name);
    timer[name] = timers.setTimeout(() => {
      timer[name] = null;
      fn();
    }, ms);
  };

  const setSession = (session) => {
    publish({ session });
    if (session.gone) shutDown("gone");
  };

  const halt = () => {
    stopped = true;
    Object.keys(timer).forEach(clear);
    if (socket) socket.close(1000);
    socket = null;
    const lost = closing && state.session ? state.session.dropped.slice(closing.dropped) : [];
    if (lost.length && onLost) onLost({ pack: state.session.pack, lost });
  };

  const shutDown = (status) => {
    publish({ status });
    halt();
  };

  const unsent = () => Boolean(state.session?.pending.some((entry) => entry.state === "queued" || entry.state === "sent"));

  // Stopped, and nothing is left to send or waiting to be sent again: done.
  const drained = () => {
    if (closing && !stopped && !sending && timer.send === null && !unsent()) halt();
  };

  // -- catching up --------------------------------------------------------------

  // This copy disagreed with the server (it never should): take the server's again, keeping what is pending.
  const reload = async () => {
    try {
      const pack = await api.getPack(packUuid);
      if (active()) setSession(reloadSession(state.session, pack));
    } catch {
      // The next poll, event or reconnect tries again.
    }
  };

  const catchUp = async () => {
    if (catchingUp) {
      catchUpAgain = true;
      return;
    }
    catchingUp = true;
    try {
      do {
        catchUpAgain = false;
        let more = true;
        while (more && active()) {
          const page = await api.getEvents(packUuid, state.session.seq);
          if (!active()) return;
          const { session } = receive(state.session, page.events);
          setSession(session);
          if (session.diverged) {
            await reload();
            more = false;
          } else {
            more = page.has_more;
          }
        }
      } while (catchUpAgain && active());
    } catch (error) {
      const failure = failureOf(error);
      if (failure.status === 404 || failure.status === 403) shutDown("gone");
      // Anything else: the next poll, event or reconnect tries again.
    } finally {
      catchingUp = false;
    }
    flush();
  };

  const poll = () => {
    if (!active() || state.status !== "polling") return;
    later("poll", () => {
      if (!isHidden()) catchUp();
      poll();
    }, POLL_MS);
  };

  const startPolling = () => {
    if (!active()) return;
    if (state.status !== "polling") publish({ status: "polling" });
    if (timer.poll === null) poll();
  };

  // -- the live stream --------------------------------------------------------------

  const connect = () => {
    const url = state.session.pack.live_url;
    if (!active() || !url) return;
    welcomed = false;
    let ws;
    try {
      ws = openSocket(url);
    } catch {
      scheduleReconnect();
      return;
    }
    socket = ws;
    ws.onopen = () => ws.send(JSON.stringify({ type: "hello", pack: packUuid, token: getToken() }));
    ws.onmessage = (message) => onMessage(ws, message);
    ws.onclose = (closed) => onClose(ws, closed);
    ws.onerror = () => {};
  };

  const onMessage = (ws, raw) => {
    if (ws !== socket) return;
    let message;
    try {
      message = JSON.parse(raw.data);
    } catch {
      return;
    }
    if (!message || message.pack !== packUuid) return;
    if (message.type === "welcome") {
      welcomed = true;
      socketRetry = 0;
      clear("poll");
      publish({ status: "live" });
      if (message.head_seq > state.session.seq) catchUp();
      if (focus !== null) sendPresence();
    } else if (message.type === "event") {
      if (message.seq === state.session.seq + 1 && !catchingUp) {
        const { session, gap } = receive(state.session, [message.event]);
        setSession(session);
        if (session.diverged) reload();
        else if (gap) catchUp();
        flush();
      } else if (message.seq > state.session.seq) {
        catchUp();
      }
    } else if (message.type === "head") {
      if (message.seq > state.session.seq) catchUp();
    } else if (message.type === "resync") {
      catchUp();
    } else if (message.type === "presence") {
      publish({ people: message.people });
    }
  };

  const onClose = (ws, closed) => {
    if (ws !== socket) return;
    socket = null;
    welcomed = false;
    if (stopped) return;
    publish({ people: [] });
    const code = closed?.code;
    if (code === 4403 || code === 4404 || code === 4410) {
      shutDown("gone");
      return;
    }
    // Everything else, a refused token included: carry on by asking, and try the stream again.
    startPolling();
    if (code !== 4400) scheduleReconnect();
  };

  const scheduleReconnect = () => {
    const ms = RETRY_MS[Math.min(socketRetry, RETRY_MS.length - 1)];
    socketRetry += 1;
    later("reconnect", connect, ms);
  };

  // -- sending ------------------------------------------------------------------------

  const flush = () => {
    if (stopped || sending || !state.session) return;
    if (closing && getToken() !== closing.token) {
      // Signed out, or someone else signed in, since it was stopped: never sent as anyone but who made them.
      setSession(abandon(state.session, "signed_out"));
      halt();
      return;
    }
    const next = nextBatch(state.session);
    if (!next) return;
    sending = true;
    setSession(next.session);
    api.sendOps(packUuid, next.batch).then(
      (answer) => {
        sending = false;
        sendRetry = 0;
        if (stopped) return;
        const result = batchAnswered(state.session, answer);
        setSession(result.session);
        if (result.catchUp && active()) catchUp();
        flush();
        drained();
      },
      (error) => {
        sending = false;
        if (stopped) return;
        const failure = failureOf(error);
        if (closing && failure.status === 401) {
          // Nobody is left to sign in again for these.
          setSession(abandon(state.session, "signed_out"));
          halt();
          return;
        }
        setSession(batchFailed(state.session, failure));
        if (failure.status === 0 || failure.status === 401 || failure.status === 429 || failure.status >= 500) {
          const ms = RETRY_MS[Math.min(sendRetry, RETRY_MS.length - 1)];
          sendRetry += 1;
          later("send", flush, ms);
        } else {
          flush();
        }
        drained();
      },
    );
  };

  // -- presence -------------------------------------------------------------------------

  const sendPresence = () => {
    if (!socket || !welcomed) return;
    if (timer.presence !== null) {
      focusSent = false;
      return;
    }
    socket.send(JSON.stringify({ type: "presence", focus }));
    focusSent = true;
    later("presence", () => {
      if (!focusSent) sendPresence();
    }, PRESENCE_MS);
  };

  // -- loading ----------------------------------------------------------------------------

  const load = async () => {
    loading = true;
    try {
      const pack = await api.getPack(packUuid);
      if (!active()) return;
      loadRetry = 0;
      publish({ session: openSession(pack, me), error: null });
      startPolling();
      connect();
    } catch (error) {
      if (!active()) return;
      const failure = failureOf(error);
      if (failure.status === 404 || failure.status === 403) {
        publish({ error: failure });
        shutDown("gone");
        return;
      }
      publish({ status: "error", error: failure });
      // A server down or no connection: tried again, as sends are. Not while the page is hidden,
      // where wake() takes over when it is shown.
      const ms = RETRY_MS[Math.min(loadRetry, RETRY_MS.length - 1)];
      loadRetry += 1;
      later("load", () => {
        if (!isHidden()) load();
      }, ms);
    } finally {
      loading = false;
    }
  };

  // -- the client ---------------------------------------------------------------------

  return {
    getState: () => state,

    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },

    start() {
      return load();
    },

    /** The connection is back, or the page is shown again: a pack that could not be loaded is tried now. */
    wake() {
      if (!active() || loading || state.session || state.status !== "error") return;
      clear("load");
      load();
    },

    /** Closes the stream and asks nothing more; what is still waiting is sent first (see above). */
    stop() {
      if (closing || stopped) return;
      closing = { token: getToken(), dropped: state.session?.dropped.length ?? 0 };
      ["poll", "reconnect", "presence", "load"].forEach(clear);
      if (socket) socket.close(1000);
      socket = null;
      drained();
    },

    /** Make edits: shown at once, sent in order. Returns why they were refused, or null. */
    edit(ops) {
      if (stopped) return closing ? "closed" : "gone";
      if (!state.session) return "loading";
      const { session, refused } = editSession(state.session, ops, newId);
      if (refused) return refused;
      setSession(session);
      flush();
      return null;
    },

    /** What this person has open or is working on, for everyone else's presence. Small: a few fields. */
    setFocus(next) {
      focus = next ?? null;
      sendPresence();
    },

    /** Asks for whatever is new now: after a change made outside the operation stream (a copy from the library). */
    refresh() {
      if (state.session && active()) catchUp();
    },
  };
};
