import { useCallback, useState } from "react";
import api from "../auth/api";
import { parseKmz } from "./parseKmz";

/*
 * The map overlays open in this session, and the ones kept in the Library. An overlay is this
 * person's own map layer, drawn over whichever workspace is showing and never part of a pack. Web
 * only, with no sync: what is kept is the file as it came, read again each time it is opened
 * (routes/overlay_routes.py).
 */

const newId = () => `overlay-${Date.now()}-${Math.random().toString(36).slice(2)}`;

export const useOverlays = () => {
  const [overlays, setOverlays] = useState([]);
  const [savedOverlays, setSavedOverlays] = useState([]);
  const [isLoadingSaved, setIsLoadingSaved] = useState(false);
  const [savedError, setSavedError] = useState(null);
  // Where the map should go next: { bounds, key }, the key so the same place can be asked for twice.
  const [fit, setFit] = useState(null);

  const goTo = useCallback((doc) => {
    if (doc?.bounds) setFit((prev) => ({ bounds: doc.bounds, key: (prev?.key ?? 0) + 1 }));
  }, []);

  const place = useCallback(
    (doc, file, { name, savedId = null } = {}) => {
      const overlay = { id: newId(), name: name || doc.name.toUpperCase(), fileName: file.name, visible: true, savedId, doc, file };
      setOverlays((prev) => [...prev, overlay]);
      goTo(doc);
      return overlay;
    },
    [goTo],
  );

  /** `parsed`: what the import's review already read, so a large file is not read twice. */
  const importFile = useCallback(
    async (file, { name, parsed } = {}) => place(parsed ?? (await parseKmz(file)), file, { name }),
    [place],
  );

  const toggle = useCallback((id) => {
    setOverlays((prev) => prev.map((overlay) => (overlay.id === id ? { ...overlay, visible: !overlay.visible } : overlay)));
  }, []);

  const remove = useCallback((id) => setOverlays((prev) => prev.filter((overlay) => overlay.id !== id)), []);

  const fetchSaved = useCallback(async () => {
    setIsLoadingSaved(true);
    try {
      const res = await api.get("/overlays");
      setSavedOverlays(res.data);
      setSavedError(null);
    } catch (err) {
      // 401s are handled (sign-out) by the api interceptor; anything else is shown where the list is.
      if (err.response?.status !== 401) setSavedError(err);
    } finally {
      setIsLoadingSaved(false);
    }
  }, []);

  /** Keeps an open overlay in the Library. The record is the file, so there is nothing to update later. */
  const save = useCallback(
    async (overlay) => {
      const form = new FormData();
      form.append("name", overlay.name);
      form.append("summary", overlay.doc.summary ?? "");
      form.append("file", overlay.file, overlay.fileName);
      const { data } = await api.post("/overlays", form);
      setOverlays((prev) => prev.map((one) => (one.id === overlay.id ? { ...one, savedId: data.id } : one)));
      fetchSaved();
      return data;
    },
    [fetchSaved],
  );

  /** Opens one from the Library; one already open is shown and gone to rather than opened twice. */
  const openSaved = useCallback(
    async (entry) => {
      const already = overlays.find((overlay) => overlay.savedId === entry.id);
      if (already) {
        setOverlays((prev) => prev.map((overlay) => (overlay.id === already.id ? { ...overlay, visible: true } : overlay)));
        goTo(already.doc);
        return already;
      }
      const res = await api.get(`/overlays/${entry.id}/file`, { responseType: "blob" });
      const file = new File([res.data], entry.file_name || `${entry.name}.kmz`);
      return place(await parseKmz(file), file, { name: entry.name, savedId: entry.id });
    },
    [overlays, goTo, place],
  );

  /** Gone from the Library. A copy open on the map stays there, no longer saved. */
  const deleteSaved = useCallback(
    async (id) => {
      await api.delete(`/overlays/${id}`);
      setOverlays((prev) => prev.map((overlay) => (overlay.savedId === id ? { ...overlay, savedId: null } : overlay)));
      fetchSaved();
    },
    [fetchSaved],
  );

  const renamedInLibrary = useCallback((id, name) => {
    setOverlays((prev) => prev.map((overlay) => (overlay.savedId === id ? { ...overlay, name } : overlay)));
  }, []);

  const zoomTo = useCallback((overlay) => goTo(overlay.doc), [goTo]);

  return { overlays, savedOverlays, isLoadingSaved, savedError, fit, importFile, toggle, remove, zoomTo, fetchSaved, save, openSaved, deleteSaved, renamedInLibrary };
};
