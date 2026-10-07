import api from "../auth/api";
import * as packApi from "./packApi";

jest.mock("../auth/api", () => ({
  get: jest.fn(),
  post: jest.fn(),
  put: jest.fn(),
  delete: jest.fn(),
}));

const ANSWER = { ok: true };

beforeEach(() => {
  ["get", "post", "put", "delete"].forEach((method) => {
    api[method].mockReset();
    api[method].mockResolvedValue({ data: ANSWER });
  });
});

// [call, method, path, body or query] for every route in contracts/openapi.yaml under packs, teams,
// invites and people. The path is what axios gets: relative to REACT_APP_API_URL, which ends in /api.
const CASES = [
  [() => packApi.listPacks(), "get", "/packs"],
  [() => packApi.createPack({ name: "OP DK", team_id: 3 }), "post", "/packs", { name: "OP DK", team_id: 3 }],
  [() => packApi.getPack("p 1"), "get", "/packs/p%201"],
  [() => packApi.updatePack("p-1", { name: "OP EAGLE" }), "put", "/packs/p-1", { name: "OP EAGLE" }],
  [() => packApi.deletePack("p-1"), "delete", "/packs/p-1"],
  [() => packApi.finishPack("p-1"), "post", "/packs/p-1/finish"],
  [() => packApi.reopenPack("p-1"), "post", "/packs/p-1/reopen"],
  [() => packApi.duplicatePack("p-1"), "post", "/packs/p-1/duplicate", {}],
  [() => packApi.duplicatePack("p-1", "OP DK 2"), "post", "/packs/p-1/duplicate", { name: "OP DK 2" }],
  [() => packApi.getEvents("p-1", 7), "get", "/packs/p-1/events", { params: { since: 7, limit: 500 } }],
  [() => packApi.sendOps("p-1", { ops: [], base_seq: 2 }), "post", "/packs/p-1/ops", { ops: [], base_seq: 2 }],
  [() => packApi.markSeen("p-1", 9), "put", "/packs/p-1/seen", { seq: 9 }],
  [() => packApi.copyIntoPack("p-1", { source: { kind: "lz", id: 4 }, item: "lz-9" }), "post", "/packs/p-1/items",
    { source: { kind: "lz", id: 4 }, item: "lz-9" }],
  [() => packApi.getItem("p-1", "lz/9"), "get", "/packs/p-1/items/lz%2F9"],
  [() => packApi.updateFromOriginal("p-1", "lz-9"), "post", "/packs/p-1/items/lz-9/update-from-original", {}],
  [() => packApi.saveItemToLibrary("p-1", "lz-9", "MY COPY"), "post", "/packs/p-1/items/lz-9/library", { name: "MY COPY" }],
  [() => packApi.addMember("p-1", 5), "post", "/packs/p-1/members", { user_id: 5, role: "editor" }],
  [() => packApi.changeMember("p-1", 5, "viewer"), "put", "/packs/p-1/members/5", { role: "viewer" }],
  [() => packApi.removeMember("p-1", 5), "delete", "/packs/p-1/members/5"],
  [() => packApi.listPackInvites("p-1"), "get", "/packs/p-1/invites"],
  [() => packApi.inviteToPack("p-1", "a@army.mil", "viewer"), "post", "/packs/p-1/invites", { email: "a@army.mil", role: "viewer" }],
  [() => packApi.resendPackInvite("p-1", 3), "post", "/packs/p-1/invites/3/resend"],
  [() => packApi.revokePackInvite("p-1", 3), "delete", "/packs/p-1/invites/3"],
  [() => packApi.listMyInvites(), "get", "/invites"],
  [() => packApi.acceptInvite(3), "post", "/invites/3/accept"],
  [() => packApi.declineInvite(3), "post", "/invites/3/decline"],
  [() => packApi.acceptInviteLink("tok"), "post", "/invites/accept", { token: "tok" }],
  [() => packApi.listTeams(), "get", "/teams"],
  [() => packApi.createTeam("B Co"), "post", "/teams", { name: "B Co" }],
  [() => packApi.getTeam(2), "get", "/teams/2"],
  [() => packApi.renameTeam(2, "C Co"), "put", "/teams/2", { name: "C Co" }],
  [() => packApi.deleteTeam(2), "delete", "/teams/2"],
  [() => packApi.changeTeamMember(2, 5, "admin"), "put", "/teams/2/members/5", { role: "admin" }],
  [() => packApi.removeTeamMember(2, 5), "delete", "/teams/2/members/5"],
  [() => packApi.listTeamInvites(2), "get", "/teams/2/invites"],
  [() => packApi.inviteToTeam(2), "post", "/teams/2/invites", { role: "member" }],
  [() => packApi.inviteToTeam(2, { email: "a@army.mil", role: "admin" }), "post", "/teams/2/invites",
    { role: "admin", email: "a@army.mil" }],
  [() => packApi.revokeTeamInvite(2, 3), "delete", "/teams/2/invites/3"],
  [() => packApi.searchPeople("sam"), "get", "/users/search", { params: { q: "sam" } }],
];

describe("packApi", () => {
  // A rest parameter, not a fifth one: Jest hands a row that is one value short its `done` callback.
  it.each(CASES.map((c) => [`${c[1].toUpperCase()} ${c[2]}`, ...c]))("%s", async (_name, call, method, path, ...rest) => {
    await expect(call()).resolves.toBe(ANSWER);
    expect(api[method]).toHaveBeenCalledTimes(1);
    const args = api[method].mock.calls[0];
    expect(args[0]).toBe(path);
    if (rest.length) expect(args[1]).toEqual(rest[0]);
    else expect(args[1]).toBeUndefined();
  });
});

// The documented routes, read from contracts/openapi.yaml: each path is a two-space key under `paths:`, and its
// methods four-space keys under it. (No YAML parser is a dependency of the web; the file's layout is regular.)
const documentedRoutes = () => {
  const fs = require("fs");
  const path = require("path");
  const text = fs.readFileSync(path.resolve(__dirname, "../../../../contracts/openapi.yaml"), "utf8");
  const routes = [];
  let current = null;
  text.split(/\r?\n/).forEach((line) => {
    const pathKey = line.match(/^ {2}(\/api\/[^:\s]+):\s*$/);
    if (pathKey) current = pathKey[1];
    else if (/^\S/.test(line) || /^ {2}\S/.test(line)) current = null;
    const method = current && line.match(/^ {4}(get|post|put|delete):\s*$/);
    if (method) routes.push(`${method[1].toUpperCase()} ${current}`);
  });
  return routes;
};

// What a client route looks like once its values are replaced by the spec's parameter names.
const asSpecRoute = (method, path) =>
  `${method.toUpperCase()} /api${path}`
    .replace(/^(\S+ \/api\/packs\/)[^/]+/, "$1{uuid}")
    .replace(/\/items\/[^/]+/, "/items/{item}")
    .replace(/\/members\/[^/]+/, "/members/{user_id}")
    .replace(/\/invites\/(?!accept$)[^/]+/, "/invites/{invite_id}")
    .replace(/^(\S+ \/api\/teams\/)[^/]+/, "$1{id}");

describe("packApi and the contract", () => {
  it("has a call for every documented pack, team, invitation and people route", () => {
    const documented = documentedRoutes().filter((route) => /\/api\/(packs|teams|invites|users)\b/.test(route));
    expect(documented.length).toBeGreaterThan(25);
    const covered = new Set(CASES.map(([, method, path]) => asSpecRoute(method, path)));
    // The live service asks /access with a person's token before it opens their socket; no screen needs it.
    const missing = documented.filter((route) => !covered.has(route) && route !== "GET /api/packs/{uuid}/access");
    expect(missing).toEqual([]);
  });
});

const refused = (status, data) => Object.assign(new Error("refused"), { response: { status, data } });

describe("packErrorMessage", () => {
  it("says what the server's code means, in the app's words, never the server's text", () => {
    const error = refused(423, { code: "pack_finished", error: "This pack was finished by Colin." });
    expect(packApi.packErrorMessage(error)).toBe("This pack is finished. It is read-only until its owner reopens it.");
    expect(packApi.packErrorMessage(refused(500, { error: "KeyError: 'name'" }))).toBe(
      "Something went wrong on the server. Try again.");
  });

  it("tells no answer apart from a refusal, even when the error carries its own status", () => {
    const offline = Object.assign(new Error("Network Error"), { status: undefined });
    expect(packApi.packErrorMessage(offline)).toMatch(/no connection/);
    expect(packApi.packErrorMessage({ status: 0 })).toMatch(/no connection/);
  });

  it("reads a failure that was already read", () => {
    expect(packApi.packErrorMessage({ status: 410, code: "invite_expired" })).toMatch(/expired/);
    expect(packApi.packErrorMessage({ status: 404 })).toBe("That pack is gone, or you are no longer in it.");
    expect(packApi.packErrorMessage({ status: 403 })).toBe("You do not have permission to do that.");
  });
});
