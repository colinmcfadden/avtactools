import { useCallback, useEffect, useState } from "react";
import * as packApi from "../packApi";

/*
 * What the workspace switcher lists: the packs this person can open, the invitations waiting for
 * them, and their teams. Loaded when packs are turned on for them, and again when the switcher opens
 * (someone may have added them meanwhile). A failed load keeps what was there.
 */
export const usePacksHome = ({ enabled, api = packApi }) => {
  const [packs, setPacks] = useState([]);
  const [invites, setInvites] = useState([]);
  const [teams, setTeams] = useState([]);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState(null);

  const refresh = useCallback(async () => {
    if (!enabled) return;
    try {
      const [p, i, t] = await Promise.all([api.listPacks(), api.listMyInvites(), api.listTeams()]);
      setPacks(p?.packs ?? []);
      setInvites(i?.invites ?? []);
      setTeams(t?.teams ?? []);
      setError(null);
    } catch (err) {
      setError(err);
    } finally {
      setLoaded(true);
    }
  }, [enabled, api]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  return { packs, invites, teams, loaded, error, refresh, setPacks };
};

export default usePacksHome;
