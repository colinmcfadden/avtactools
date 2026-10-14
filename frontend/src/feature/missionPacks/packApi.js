import api from "../auth/api";

// Every mission-pack, team and invitation route (contracts/openapi.yaml, docs/MISSION_PACKS.md §4).
// Paths are relative to REACT_APP_API_URL, which already ends in /api. Each call resolves to the
// response body; a refusal rejects with the axios error, which failureOf reads.

const pack = (uuid) => `/packs/${encodeURIComponent(uuid)}`;
const item = (uuid, itemUuid) => `${pack(uuid)}/items/${encodeURIComponent(itemUuid)}`;
const team = (id) => `/teams/${encodeURIComponent(id)}`;
const body = (response) => response.data;

// -- Packs ------------------------------------------------------------------------

export const listPacks = () => api.get("/packs").then(body);

/** `fields`: { name, description?, team_id?, team_role?, uuid? }. */
export const createPack = (fields) => api.post("/packs", fields).then(body);

export const getPack = (uuid) => api.get(pack(uuid)).then(body);

/** `fields`: any of { name, description } (editors), { team_id, team_role } (the owner). */
export const updatePack = (uuid, fields) => api.put(pack(uuid), fields).then(body);

export const deletePack = (uuid) => api.delete(pack(uuid)).then(body);

export const finishPack = (uuid) => api.post(`${pack(uuid)}/finish`).then(body);

export const reopenPack = (uuid) => api.post(`${pack(uuid)}/reopen`).then(body);

export const duplicatePack = (uuid, name) =>
  api.post(`${pack(uuid)}/duplicate`, name === undefined ? {} : { name }).then(body);

// -- The log and edits ---------------------------------------------------------------

export const getEvents = (uuid, since, limit = 500) =>
  api.get(`${pack(uuid)}/events`, { params: { since, limit } }).then(body);

export const sendOps = (uuid, batch) => api.post(`${pack(uuid)}/ops`, batch).then(body);

/** How far this person has looked (never goes back on the server). */
export const markSeen = (uuid, seq) => api.put(`${pack(uuid)}/seen`, { seq }).then(body);

// -- Items ---------------------------------------------------------------------------

/**
 * Copies one of the person's library records in. `source`: { kind: "lz"|"route"|"pointset", id } or
 * { kind, client_uuid }. Pass `item` (the new item's uuid) so a retry returns the first copy.
 */
export const copyIntoPack = (uuid, { source, item: itemUuid, name, summary } = {}) =>
  api
    .post(`${pack(uuid)}/items`, {
      source,
      ...(itemUuid !== undefined && { item: itemUuid }),
      ...(name !== undefined && { name }),
      ...(summary !== undefined && { summary }),
    })
    .then(body);

export const getItem = (uuid, itemUuid) => api.get(item(uuid, itemUuid)).then(body);

export const updateFromOriginal = (uuid, itemUuid, summary) =>
  api.post(`${item(uuid, itemUuid)}/update-from-original`, summary === undefined ? {} : { summary }).then(body);

export const saveItemToLibrary = (uuid, itemUuid, name) =>
  api.post(`${item(uuid, itemUuid)}/library`, name === undefined ? {} : { name }).then(body);

// -- Members and invitations to a pack ---------------------------------------------------

/** A teammate, added directly. Anyone else is invited by email. */
export const addMember = (uuid, userId, role = "editor") =>
  api.post(`${pack(uuid)}/members`, { user_id: userId, role }).then(body);

/** `role` "owner" hands the pack over. */
export const changeMember = (uuid, userId, role) =>
  api.put(`${pack(uuid)}/members/${encodeURIComponent(userId)}`, { role }).then(body);

/** The owner removing someone, or a person leaving (their own id). */
export const removeMember = (uuid, userId) =>
  api.delete(`${pack(uuid)}/members/${encodeURIComponent(userId)}`).then(body);

export const listPackInvites = (uuid) => api.get(`${pack(uuid)}/invites`).then(body);

export const inviteToPack = (uuid, email, role = "editor") =>
  api.post(`${pack(uuid)}/invites`, { email, role }).then(body);

export const resendPackInvite = (uuid, inviteId) =>
  api.post(`${pack(uuid)}/invites/${encodeURIComponent(inviteId)}/resend`).then(body);

export const revokePackInvite = (uuid, inviteId) =>
  api.delete(`${pack(uuid)}/invites/${encodeURIComponent(inviteId)}`).then(body);

// -- The person's own invitations ---------------------------------------------------------

export const listMyInvites = () => api.get("/invites").then(body);

export const acceptInvite = (inviteId) => api.post(`/invites/${encodeURIComponent(inviteId)}/accept`).then(body);

export const declineInvite = (inviteId) => api.post(`/invites/${encodeURIComponent(inviteId)}/decline`).then(body);

/** From an emailed or shared link (`?invite=`), whichever address the person signed in with. */
export const acceptInviteLink = (token) => api.post("/invites/accept", { token }).then(body);

// -- Teams ----------------------------------------------------------------------------

export const listTeams = () => api.get("/teams").then(body);

export const createTeam = (name) => api.post("/teams", { name }).then(body);

export const getTeam = (id) => api.get(team(id)).then(body);

export const renameTeam = (id, name) => api.put(team(id), { name }).then(body);

export const deleteTeam = (id) => api.delete(team(id)).then(body);

/** `role` "owner" hands the team over; "admin" or "member" otherwise. */
export const changeTeamMember = (id, userId, role) =>
  api.put(`${team(id)}/members/${encodeURIComponent(userId)}`, { role }).then(body);

export const removeTeamMember = (id, userId) =>
  api.delete(`${team(id)}/members/${encodeURIComponent(userId)}`).then(body);

export const listTeamInvites = (id) => api.get(`${team(id)}/invites`).then(body);

/** With an email, an emailed invitation; without, a single-use link whose `token` is in the answer this once. */
export const inviteToTeam = (id, { email, role = "member" } = {}) =>
  api.post(`${team(id)}/invites`, { role, ...(email !== undefined && { email }) }).then(body);

export const revokeTeamInvite = (id, inviteId) =>
  api.delete(`${team(id)}/invites/${encodeURIComponent(inviteId)}`).then(body);

// -- People -----------------------------------------------------------------------------

/** People who share a team with the caller, by name or sign-in address. 2 to 100 characters. */
export const searchPeople = (query) => api.get("/users/search", { params: { q: query } }).then(body);

// -- Refusals ---------------------------------------------------------------------------

/**
 * What a refused request said, in the shape packSession.batchFailed reads. Status 0: no answer came. `taken` is what of a
 * refused batch the pack already has (POST .../ops's 403 pack_read_only, 413 and 423); undefined from an older server.
 */
export const failureOf = (error) => {
  const response = error?.response;
  if (!response) return { status: 0 };
  const data = response.data && typeof response.data === "object" ? response.data : {};
  return { status: response.status, code: data.code, reason: data.reason, finished_by: data.finished_by, finished_at: data.finished_at,
    taken: data.taken };
};

const MESSAGES = {
  feature_disabled: "Mission Packs are not turned on for your account yet.",
  pack_not_found: "That pack is gone, or you are no longer in it.",
  pack_read_only: "You can view this pack but not change it.",
  owner_only: "Only the pack's owner can do that.",
  pack_finished: "This pack is finished. It is read-only until its owner reopens it.",
  invalid_name: "Give it a name of 1 to 100 characters.",
  invalid_description: "The description can be 2,000 characters at most.",
  item_too_large: "That is larger than a pack item can be (5 MB).",
  item_not_found: "That item is no longer in the pack.",
  item_exists: "The pack already has that item.",
  source_not_found: "That is no longer in your Library.",
  mission_not_supported: "Imported AMPS missions cannot go in a pack yet. Sketched routes can.",
  unreadable_source: "That Library item cannot be read.",
  not_your_original: "Only the person who copied this in can update it from the original.",
  original_gone: "The original is no longer in your Library.",
  empty_point_set: "A point set with no points cannot be saved.",
  not_a_teammate: "Only people on one of your teams can be added by name. Invite anyone else by email.",
  already_member: "They are already in it.",
  member_not_found: "They are no longer a member.",
  owner_must_transfer: "Make someone else the owner first.",
  invalid_email: "Enter a valid email address.",
  invalid_role: "Choose a role.",
  rate_limited: "Too many invitations for now. Try again later.",
  invite_gone: "That invitation has already been used or was withdrawn.",
  invite_expired: "That invitation has expired. Ask whoever sent it for a new one.",
  invite_not_found: "That invitation link is not valid.",
  team_not_found: "That team is gone, or you are no longer in it.",
  team_managers_only: "Only the team's owner or an admin can do that.",
  not_in_team: "You can only share a pack with a team you are in.",
  invalid_query: "Type at least two characters.",
};

/**
 * Words for a person about a refused or failed request. Only the app's own: a server's text is
 * never shown, since a failure the server did not mean to happen can carry its internals.
 */
export const packErrorMessage = (errorOrFailure) => {
  // A failure already read by failureOf, or the request's error itself (an Error, which carries its own
  // `status` in newer axios even when no answer came).
  const isFailure = errorOrFailure && !(errorOrFailure instanceof Error) && typeof errorOrFailure.status === "number";
  const failure = isFailure ? errorOrFailure : failureOf(errorOrFailure);
  if (failure.status === 0) return "There is no connection to the server. Try again when you are back online.";
  if (failure.code && MESSAGES[failure.code]) return MESSAGES[failure.code];
  if (failure.status === 404) return MESSAGES.pack_not_found;
  if (failure.status === 403) return "You do not have permission to do that.";
  if (failure.status === 429) return "Too many requests. Wait a moment and try again.";
  return "Something went wrong on the server. Try again.";
};
