import { useCallback, useEffect, useMemo, useState } from "react";
import api from "../auth/api";
import {
  FALLBACK_PROFILE,
  findProfile,
  normalizeProfile,
} from "./aircraftProfiles";

const ACTIVE_PROFILE_KEY = "avtac.aircraftProfile";

/**
 * The aircraft profile list and the mission's default aircraft.
 *
 * The list is the admin-managed master profiles plus anything the user built
 * themselves. Until it loads — or if the request fails — everything falls back
 * to the built-in UH-60L, so the map and planner always have real geometry to
 * work with rather than rendering nothing.
 *
 * The active profile is remembered by slug (not id), so it survives a database
 * change and matches how saved maps reference aircraft.
 */
export const useAircraftProfiles = ({ enabled = true } = {}) => {
  const [profiles, setProfiles] = useState([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState("");
  const [activeRef, setActiveRef] = useState(
    () => localStorage.getItem(ACTIVE_PROFILE_KEY) || FALLBACK_PROFILE.slug,
  );

  const load = useCallback(async () => {
    if (!enabled) {
      setIsLoading(false);
      return;
    }
    setIsLoading(true);
    try {
      const res = await api.get("/aircraft-profiles");
      const list = Array.isArray(res.data) ? res.data.map(normalizeProfile) : [];
      setProfiles(list);
      setError("");
    } catch (err) {
      // 401 is handled globally by the api interceptor; anything else just
      // means we stay on the built-in profile.
      if (err.response?.status !== 401) {
        setError("Couldn't load aircraft profiles — using the default UH-60L.");
      }
    } finally {
      setIsLoading(false);
    }
  }, [enabled]);

  useEffect(() => {
    load();
  }, [load]);

  const activeProfile = useMemo(() => {
    const found = findProfile(profiles, activeRef);
    if (found) return found;
    return findProfile(profiles, FALLBACK_PROFILE.slug) || FALLBACK_PROFILE;
  }, [profiles, activeRef]);

  const selectProfile = useCallback((ref) => {
    // Persist the slug so the choice survives a redeploy that renumbers ids.
    const value = typeof ref === "object" && ref !== null ? ref.slug || ref.id : ref;
    if (value == null) return;
    setActiveRef(String(value));
    try {
      localStorage.setItem(ACTIVE_PROFILE_KEY, String(value));
    } catch {
      // Private browsing with storage disabled — the choice just won't persist.
    }
  }, []);

  const createProfile = useCallback(async (payload) => {
    const res = await api.post("/aircraft-profiles", payload);
    const created = normalizeProfile(res.data);
    setProfiles((previous) => [...previous, created]);
    return created;
  }, []);

  const updateProfile = useCallback(async (id, payload) => {
    const res = await api.put(`/aircraft-profiles/${id}`, payload);
    const updated = normalizeProfile(res.data);
    setProfiles((previous) => previous.map((p) => (p.id === id ? updated : p)));
    return updated;
  }, []);

  const deleteProfile = useCallback(async (id) => {
    await api.delete(`/aircraft-profiles/${id}`);
    setProfiles((previous) => previous.filter((p) => p.id !== id));
  }, []);

  const masterProfiles = useMemo(() => profiles.filter((p) => p.is_system), [profiles]);
  const customProfiles = useMemo(() => profiles.filter((p) => !p.is_system), [profiles]);

  return {
    profiles,
    masterProfiles,
    customProfiles,
    activeProfile,
    selectProfile,
    isLoading,
    error,
    reload: load,
    createProfile,
    updateProfile,
    deleteProfile,
  };
};
