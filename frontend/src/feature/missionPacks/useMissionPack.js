import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import * as packApi from "./packApi";
import { createPackClient } from "./packClient";

const CLOSED = { status: "closed", session: null, items: [], people: [], error: null };
const nothing = () => () => {};
const closed = () => CLOSED;

/**
 * One open mission pack, kept in step with everyone else's edits.
 *
 * Returns the client's state ({ status, session, items, people, error }) and
 * `edit(ops)` / `setFocus(focus)` / `refresh()`. `items` is what the person sees, their own
 * unconfirmed edits included; `session.dropped` lists edits the server will never
 * take (a finished pack, say), so the UI can offer to save them to the library.
 * `me` is the signed-in user's id. No pack (`packUuid` null) is "closed"; one that could not be
 * loaded ("error") is tried again by itself.
 *
 * Closing a pack (another, or none) still sends the edits waiting for it; `onLost({ pack, lost })`
 * hears of any it would not take by then.
 */
export const useMissionPack = (packUuid, me, { openSocket = (url) => new WebSocket(url), api = packApi, onLost } = {}) => {
  const [client, setClient] = useState(null);
  const lostRef = useRef(onLost);
  lostRef.current = onLost;

  useEffect(() => {
    if (!packUuid) return undefined;
    const next = createPackClient({
      packUuid,
      me,
      api,
      openSocket,
      getToken: () => localStorage.getItem("auth_token"),
      onLost: (lost) => lostRef.current?.(lost),
    });
    setClient(next);
    next.start();
    // A pack that could not be loaded is tried again at once when the connection or the page comes back.
    const wake = () => {
      if (!document.hidden) next.wake();
    };
    window.addEventListener("online", wake);
    document.addEventListener("visibilitychange", wake);
    return () => {
      window.removeEventListener("online", wake);
      document.removeEventListener("visibilitychange", wake);
      next.stop();
      setClient(null);
    };
    // A new socket factory or API each render must not reopen the pack.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [packUuid, me]);

  const state = useSyncExternalStore(client ? client.subscribe : nothing, client ? client.getState : closed);
  const edit = useCallback((ops) => (client ? client.edit(ops) : "closed"), [client]);
  const setFocus = useCallback((focus) => client?.setFocus(focus), [client]);
  const refresh = useCallback(() => client?.refresh(), [client]);
  return { ...state, edit, setFocus, refresh };
};
