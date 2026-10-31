import api from "../auth/api";

/*
 * What the Library does to saved records beyond listing, opening and deleting them (those stay in
 * useSavedMaps, useSavedRoutes and useLocalPoints): renaming and duplicating. A rename sends only the
 * name; a duplicate reads the record and saves it again under the new name.
 */

const routeForm = (fields) => {
  const form = new FormData();
  Object.entries(fields).forEach(([key, value]) => {
    if (value === undefined || value === null) return;
    if (value instanceof Blob) form.append(key, value, value.name || "mission.msnx");
    else form.append(key, typeof value === "string" ? value : JSON.stringify(value));
  });
  return form;
};

export const renameRecord = async (kind, id, name) => {
  if (kind === "lz") return (await api.put(`/lz/${id}`, { name })).data;
  if (kind === "points") return (await api.put(`/pointsets/${id}`, { name })).data;
  return (await api.put(`/routes/${id}`, routeForm({ name }))).data;
};

export const duplicateRecord = async (kind, entry, name) => {
  if (kind === "lz") {
    const { data } = await api.get(`/lz/${entry.id}`);
    return (await api.post("/lz", { name, lz_data: { ...data.lz_data, name } })).data;
  }
  if (kind === "points") {
    const { data } = await api.get(`/pointsets/${entry.id}`);
    return (await api.post("/pointsets", { name, points: data.points })).data;
  }
  const { data } = await api.get(`/routes/${entry.id}`);
  const fields = { name, kind: data.kind, route_data: data.route_data };
  if (data.kind === "mission" && data.has_file) {
    const file = await api.get(`/routes/${entry.id}/file`, { responseType: "blob" });
    fields.msnx = new File([file.data], data.file_name || `${name}.msnx`);
  }
  return (await api.post("/routes", routeForm(fields))).data;
};
