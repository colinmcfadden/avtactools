import { useState } from "react";
import api from "../auth/api";
import { makeThreat, threatToPayload } from "./threatModel";
import { parseThsFile } from "./parseThs";

const downloadBlob = (blob, filename) => {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
};

/**
 * In-memory threats (export-only — deliberately never persisted). Manages the
 * threat list, the add/edit dialog target, and terrain-mask fetching from the
 * backend viewshed endpoint.
 */
export const useThreats = () => {
  const [threats, setThreats] = useState([]);
  // The threat currently open in the dialog: { threat, isNew }.
  const [editingThreat, setEditingThreat] = useState(null);

  const patchThreat = (id, patch) =>
    setThreats((prev) => prev.map((t) => (t.id === id ? { ...t, ...patch } : t)));

  /** Recomputes a threat's terrain mask from the backend. */
  const fetchMask = async (threat) => {
    patchThreat(threat.id, { maskLoading: true, maskError: null });
    try {
      const res = await api.post("/threat-mask", {
        lat: threat.lat,
        lon: threat.lon,
        radars: threatToPayload(threat).radars,
      });
      patchThreat(threat.id, { mask: res.data, maskLoading: false });
    } catch (err) {
      patchThreat(threat.id, {
        maskLoading: false,
        maskError: err.response?.data?.error || err.message,
      });
    }
  };

  /** Opens the dialog for a brand-new threat at a location. */
  const beginAddThreat = (lat, lon) => {
    const n = threats.length + 1;
    setEditingThreat({ threat: makeThreat(lat, lon, { name: `Threat ${n}` }), isNew: true });
  };

  const beginEditThreat = (id) => {
    const threat = threats.find((t) => t.id === id);
    if (threat) setEditingThreat({ threat, isNew: false });
  };

  const cancelEdit = () => setEditingThreat(null);

  /** Commits the dialog: adds or updates the threat, then (re)computes its mask. */
  const saveThreat = (threat) => {
    setThreats((prev) => {
      const exists = prev.some((t) => t.id === threat.id);
      return exists ? prev.map((t) => (t.id === threat.id ? threat : t)) : [...prev, threat];
    });
    setEditingThreat(null);
    // Mask fetch reads the just-saved threat directly (state update is async).
    fetchMask(threat);
  };

  const removeThreat = (id) =>
    setThreats((prev) => prev.filter((t) => t.id !== id));

  const toggleVisibility = (id) =>
    setThreats((prev) => prev.map((t) => (t.id === id ? { ...t, visible: !t.visible } : t)));

  /** Drag-move a threat marker: reposition and recompute its mask. */
  const moveThreat = (id, lat, lon) => {
    const current = threats.find((t) => t.id === id);
    if (!current) return;
    const moved = { ...current, lat, lon };
    setThreats((prev) => prev.map((t) => (t.id === id ? moved : t)));
    fetchMask(moved);
  };

  /**
   * Builds a .ths from the current threats and downloads it. Called alongside a
   * mission (.msnx) export so the two files travel together into AMPS.
   */
  const exportThsFile = async (baseName) => {
    if (threats.length === 0) return;
    const name = `${(baseName || "mission").replace(/\.msnx$/i, "")}_threats.ths`;
    const res = await api.post(
      "/threats-ths",
      { fileName: name, threats: threats.map(threatToPayload) },
      { responseType: "blob" },
    );
    downloadBlob(res.data, name);
  };

  /**
   * Builds a KMZ overlay (vector marker + range rings + terrain-mask polygons)
   * and downloads it — the format ForeFlight, ATAK, and Aero App all import.
   */
  const exportKmzFile = async (baseName) => {
    if (threats.length === 0) return;
    const name = `${(baseName || "threats").replace(/\.(msnx|kmz)$/i, "")}_threats.kmz`;
    const res = await api.post(
      "/threats-kmz",
      { fileName: name, threats: threats.map(threatToPayload) },
      { responseType: "blob" },
    );
    downloadBlob(res.data, name);
  };

  /** Imports threats from an uploaded .ths and computes each one's mask. */
  const importThsFile = async (file) => {
    const parsed = await parseThsFile(file);
    setThreats((prev) => [...prev, ...parsed]);
    for (const t of parsed) fetchMask(t);
    return parsed.length;
  };

  return {
    threats,
    editingThreat,
    beginAddThreat,
    beginEditThreat,
    cancelEdit,
    saveThreat,
    removeThreat,
    toggleVisibility,
    moveThreat,
    fetchMask,
    importThsFile,
    exportThsFile,
    exportKmzFile,
  };
};
