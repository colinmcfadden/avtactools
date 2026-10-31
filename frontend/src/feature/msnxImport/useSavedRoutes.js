import { useState } from "react";
import api from "../auth/api";

/**
 * Cloud persistence for routes. Two kinds, matching the backend model:
 *  - "sketch":  route_data carries the sketched routes' geometry JSON.
 *  - "mission": route_data is a display summary; the authoritative data is
 *    the .msnx file (serialized from the edited in-memory state at save
 *    time), stored as bytes and re-imported on load.
 */
export const useSavedRoutes = () => {
  const [savedRoutes, setSavedRoutes] = useState([]);
  const [isLoadingSaved, setIsLoadingSaved] = useState(false);
  const [savedRoutesError, setSavedRoutesError] = useState(null);

  const fetchSavedRoutes = async () => {
    setIsLoadingSaved(true);
    try {
      const res = await api.get("/routes");
      setSavedRoutes(res.data);
      setSavedRoutesError(null);
    } catch (err) {
      // 401s are handled (sign-out) by the api interceptor; anything else is shown where the list is.
      if (err.response?.status !== 401) setSavedRoutesError(err);
    } finally {
      setIsLoadingSaved(false);
    }
  };

  const sketchForm = (name, routes) => {
    const form = new FormData();
    form.append("name", name);
    form.append("route_data", JSON.stringify({ version: 1, routes }));
    return form;
  };

  const missionForm = (name, routes, msnxBlob, fileName) => {
    const form = new FormData();
    form.append("name", name);
    form.append(
      "route_data",
      JSON.stringify({
        version: 1,
        routes: routes.map((r) => ({ name: r.name, color: r.color })),
      }),
    );
    form.append("msnx", msnxBlob, fileName);
    return form;
  };

  /** Both save functions return the created record (id, name, ...). */
  const saveSketch = async (name, routes) => {
    const form = sketchForm(name, routes);
    form.append("kind", "sketch");
    const res = await api.post("/routes", form);
    return res.data;
  };

  const saveMission = async (name, routes, msnxBlob, fileName) => {
    const form = missionForm(name, routes, msnxBlob, fileName);
    form.append("kind", "mission");
    const res = await api.post("/routes", form);
    return res.data;
  };

  /** `name` renames the saved record too; without it the name stays as it is. */
  const updateSketch = async (id, routes, name = "") => {
    const res = await api.put(`/routes/${id}`, sketchForm(name, routes));
    return res.data;
  };

  const updateMission = async (id, routes, msnxBlob, fileName, name = "") => {
    const res = await api.put(`/routes/${id}`, missionForm(name, routes, msnxBlob, fileName));
    return res.data;
  };

  /** Full record including route_data (geometry for sketches). */
  const loadSavedRoute = async (id) => {
    const res = await api.get(`/routes/${id}`);
    return res.data;
  };

  /** The stored .msnx bytes of a mission save, as a File ready to re-import. */
  const loadSavedRouteFile = async (id, fileName) => {
    const res = await api.get(`/routes/${id}/file`, { responseType: "blob" });
    return new File([res.data], fileName || "saved_route.msnx");
  };

  const deleteSavedRoute = async (id) => {
    await api.delete(`/routes/${id}`);
    setSavedRoutes((prev) => prev.filter((entry) => entry.id !== id));
  };

  return {
    savedRoutes,
    isLoadingSaved,
    savedRoutesError,
    fetchSavedRoutes,
    saveSketch,
    saveMission,
    updateSketch,
    updateMission,
    loadSavedRoute,
    loadSavedRouteFile,
    deleteSavedRoute,
  };
};
