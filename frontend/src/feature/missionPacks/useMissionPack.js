import { useCallback, useEffect, useState, useSyncExternalStore } from "react";
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
 * `me` is the signed-in user's id. No pack (`packUuid` null) is "closed".
 */
export const useMissionPack = (packUuid, me, { openSocket = (url) => new WebSocket(url), api = packApi } = {}) => {
  const [client, setClient] = useState(null);

  useEffect(() => {
    if (!packUuid) return undefined;
    const next = createPackClient({
      packUuid,
      me,
      api,
      openSocket,
      getToken: () => localStorage.getItem("auth_token"),
    });
    setClient(next);
    next.start();
    return () => {
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
