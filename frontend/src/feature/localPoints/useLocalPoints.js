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

  const importLpsFile = async (file) => {
    const parsed = await parseLpsFile(file);
    const set = {
      id: generateId("lps"),
      name: parsed.name,
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
    } catch (err) {
      // 401s are handled (alert + sign-out) by the api interceptor.
      if (err.response?.status !== 401) {
        alert("Couldn't load saved point sets: " + err.message);
      }
    } finally {
      setIsLoadingSavedSets(false);
    }
  };

  /** Saves a loaded set; overwrites its linked cloud record when one exists. */
  const savePointSet = async (setId) => {
    const set = pointSets.find((s) => s.id === setId);
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
    fetchSavedPointSets,
    savePointSet,
    loadSavedPointSet,
    deleteSavedPointSet,
  };
};
