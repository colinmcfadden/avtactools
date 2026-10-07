import { useState } from "react";
import api from "../auth/api";
import { parseLpsFile } from "./parseLps";
import { nextRouteColor } from "../msnxImport/colorPalette";

const generateId = (prefix) =>
  `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2)}`;

/**
 * Local point sets (.LPS imports) shown on the map, with cloud persistence in
 * the save menu. `savedId` links a loaded set to its cloud record so Save can
 * offer to overwrite instead of always creating a new entry.
 */
export const useLocalPoints = () => {
  const [pointSets, setPointSets] = useState([]);
  const [savedPointSets, setSavedPointSets] = useState([]);
  const [isLoadingSavedSets, setIsLoadingSavedSets] = useState(false);
  const [savedSetsError, setSavedSetsError] = useState(null);

  /** `name` names the set (the review dialog lets the person change it); else the file's own name. */
  const importLpsFile = async (file, { name } = {}) => {
    const parsed = await parseLpsFile(file);
    const set = {
      id: generateId("lps"),
      name: name || parsed.name,
      color: nextRouteColor(),
      visible: true,
      savedId: null,
      points: parsed.points,
    };
    setPointSets((prev) => [...prev, set]);
    return set;
  };

  const togglePointSetVisibility = (setId) => {
    setPointSets((prev) =>
      prev.map((set) => (set.id === setId ? { ...set, visible: !set.visible } : set)),
    );
  };

  const removePointSet = (setId) => {
    setPointSets((prev) => prev.filter((set) => set.id !== setId));
  };

  // --- cloud persistence ---

  const fetchSavedPointSets = async () => {
    setIsLoadingSavedSets(true);
    try {
      const res = await api.get("/pointsets");
      setSavedPointSets(res.data);
      setSavedSetsError(null);
    } catch (err) {
      // 401s are handled (sign-out) by the api interceptor; anything else is shown where the list is.
      if (err.response?.status !== 401) setSavedSetsError(err);
    } finally {
      setIsLoadingSavedSets(false);
    }
  };

  /**
   * Saves a loaded set; overwrites its linked cloud record when one exists. Takes the set itself as
   * well as its id, so a set imported a moment ago (not yet in this render's state) can be saved.
   */
  const savePointSet = async (setOrId) => {
    const setId = typeof setOrId === "object" ? setOrId?.id : setOrId;
    const set = typeof setOrId === "object" ? setOrId : pointSets.find((s) => s.id === setId);
    if (!set) return null;

    const payload = { name: set.name, points: set.points };
    let saved;
    if (set.savedId) {
      const res = await api.put(`/pointsets/${set.savedId}`, payload);
      saved = res.data;
    } else {
      const res = await api.post("/pointsets", payload);
      saved = res.data;
      setPointSets((prev) =>
        prev.map((s) => (s.id === setId ? { ...s, savedId: saved.id } : s)),
      );
    }
    await fetchSavedPointSets();
    return saved;
  };

  const loadSavedPointSet = async (entry) => {
    // Already on the map? Just make sure it's visible instead of duplicating.
    const existing = pointSets.find((s) => s.savedId === entry.id);
    if (existing) {
      setPointSets((prev) =>
        prev.map((s) => (s.id === existing.id ? { ...s, visible: true } : s)),
      );
      return existing;
    }

    const res = await api.get(`/pointsets/${entry.id}`);
    const set = {
      id: generateId("lps"),
      name: res.data.name,
      color: nextRouteColor(),
      visible: true,
      savedId: entry.id,
      points: res.data.points || [],
    };
    setPointSets((prev) => [...prev, set]);
    return set;
  };

  const deleteSavedPointSet = async (id) => {
    await api.delete(`/pointsets/${id}`);
    setSavedPointSets((prev) => prev.filter((entry) => entry.id !== id));
    setPointSets((prev) =>
      prev.map((s) => (s.savedId === id ? { ...s, savedId: null } : s)),
    );
  };

  return {
    pointSets,
    setPointSets,
    importLpsFile,
    togglePointSetVisibility,
    removePointSet,
    savedPointSets,
    isLoadingSavedSets,
    savedSetsError,
    fetchSavedPointSets,
    savePointSet,
    loadSavedPointSet,
    deleteSavedPointSet,
  };
};
