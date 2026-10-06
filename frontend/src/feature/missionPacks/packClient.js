import {
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
 * or no longer ours to see) or "error" (it could not be loaded).
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
}) => {
  let state = { status: "loading", session: null, items: [], people: [], error: null };
  const listeners = new Set();
  let stopped = false;
  let socket = null;
  let welcomed = false;
  let socketRetry = 0;
  let sendRetry = 0;
  let sending = false;
  let catchingUp = false;
  let catchUpAgain = false;
  const timer = { poll: null, reconnect: null, send: null, presence: null };
  let focus = null;
  let focusSent = true;

  const publish = (changes) => {
    state = { ...state, ...changes };
    if (changes.session) state.items = visibleItems(changes.session);
    listeners.forEach((listener) => listener());
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

  const shutDown = (status) => {
    publish({ status });
    stopped = true;
    Object.keys(timer).forEach(clear);
    if (socket) socket.close(1000);
    socket = null;
  };

  // -- catching up --------------------------------------------------------------

  // This copy disagreed with the server (it never should): take the server's again, keeping what is pending.
  const reload = async () => {
    try {
      const pack = await api.getPack(packUuid);
      if (!stopped) setSession(reloadSession(state.session, pack));
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
        while (more && !stopped) {
          const page = await api.getEvents(packUuid, state.session.seq);
          if (stopped) return;
          const { session } = receive(state.session, page.events);
          setSession(session);
          if (session.diverged) {
            await reload();
            more = false;
          } else {
            more = page.has_more;
          }
        }
      } while (catchUpAgain && !stopped);
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
    if (stopped || state.status !== "polling") return;
    later("poll", () => {
      if (!isHidden()) catchUp();
      poll();
    }, POLL_MS);
  };

  const startPolling = () => {
    if (stopped) return;
    if (state.status !== "polling") publish({ status: "polling" });
    if (timer.poll === null) poll();
  };

  // -- the live stream --------------------------------------------------------------

  const connect = () => {
    const url = state.session.pack.live_url;
    if (stopped || !url) return;
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
        if (result.catchUp) catchUp();
        flush();
      },
      (error) => {
        sending = false;
        if (stopped) return;
        const failure = failureOf(error);
        setSession(batchFailed(state.session, failure));
        if (failure.status === 0 || failure.status === 401 || failure.status === 429 || failure.status >= 500) {
          const ms = RETRY_MS[Math.min(sendRetry, RETRY_MS.length - 1)];
          sendRetry += 1;
          later("send", flush, ms);
        } else {
          flush();
        }
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

  // -- the client ---------------------------------------------------------------------

  return {
    getState: () => state,

    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },

    async start() {
      try {
        const pack = await api.getPack(packUuid);
        if (stopped) return;
        publish({ session: openSession(pack, me) });
        startPolling();
        connect();
      } catch (error) {
        const failure = failureOf(error);
        publish({ status: failure.status === 404 || failure.status === 403 ? "gone" : "error", error: failure });
      }
    },

    stop() {
      stopped = true;
      Object.keys(timer).forEach(clear);
      if (socket) socket.close(1000);
      socket = null;
    },

    /** Make edits: shown at once, sent in order. Returns why they were refused, or null. */
    edit(ops) {
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
  };
};
