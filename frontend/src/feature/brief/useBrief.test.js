import { act, renderHook, waitFor } from "@testing-library/react";
import { useBrief } from "./useBrief";
import { planFromPack, planFromSession, planSummary } from "./briefPlan";
import * as briefApi from "./briefApi";

jest.mock("./briefApi", () => ({
  listBriefs: jest.fn(),
  getBrief: jest.fn(),
  createBrief: jest.fn(),
  updateBrief: jest.fn(),
  weatherAt: jest.fn(),
}));

const fixture = require("../../../../contracts/fixtures/brief/compile.json");

// The design's OP DK, as this session would hold it: the fixture's plan read back into diagrams and route sets.
const OP_DK = fixture.cases[0].plan;
const DIAGRAMS = OP_DK.lzs.map((lz) => ({ ...lz.diagram, id: lz.id }));
const ROUTE_SETS = OP_DK.routeSets.map((set) => ({ key: set.id, routes: set.data.routes }));
const USER = { id: 7, name: "Colin M." };

beforeEach(() => {
  jest.useFakeTimers();
  localStorage.clear();
  Object.values(briefApi).forEach((fn) => fn?.mockReset?.());
  briefApi.weatherAt.mockResolvedValue(null);
  briefApi.createBrief.mockImplementation(async ({ name, data, clientUuid }) => ({ id: 41, name, data, client_uuid: clientUuid }));
  briefApi.updateBrief.mockResolvedValue({ id: 41 });
});
afterEach(() => {
  jest.useRealTimers();
});

describe("what a brief is built from", () => {
  it("takes a pack's live LZ/PZs and route sets by their uuids", () => {
    const items = [
      { uuid: "u-lz", kind: "lz", name: "LZ HAWK", data: { name: "LZ HAWK" } },
      { uuid: "u-rt", kind: "route", name: "ROUTES", data: { version: 1, routes: [] } },
      { uuid: "u-ps", kind: "pointset", name: "PTS", data: [] },
      { uuid: "u-br", kind: "brief", name: "BRIEF", data: {} },
      { uuid: "u-gone", kind: "lz", name: "OLD", data: null, deleted: true },
    ];
    const plan = planFromPack("OP DK", items);
    expect(plan.lzs.map((l) => l.id)).toEqual(["u-lz"]);
    expect(plan.routeSets.map((s) => [s.id, s.name])).toEqual([["u-rt", "ROUTES"]]);
    expect(planSummary(plan, 1)).toBe("1 LZ/PZ, 1 route set, 1 threat");
  });

  it("takes this session's open LZ/PZs and route sets, leaving out a set with no routes", () => {
    const plan = planFromSession("", DIAGRAMS, [...ROUTE_SETS, { key: "empty", routes: [] }]);
    expect(plan.lzs).toHaveLength(5);
    expect(plan.routeSets.map((s) => s.id)).toEqual(["set-opdk"]);
    expect(planSummary(plan)).toBe("5 LZ/PZs, 1 route set");
  });
});

describe("useBrief outside a pack", () => {
  const render = (active = true) => renderHook(() => useBrief({
    enabled: true, active, user: USER, session: { name: "", diagrams: DIAGRAMS, routeSets: ROUTE_SETS },
  }));

  it("compiles the brief from the session, in route order, before anything is answered", () => {
    const { result } = render();
    expect(result.current.hasDoc).toBe(false);
    expect(result.current.model.flow.map((f) => f.ref).slice(0, 3)).toEqual(["route:set-opdk/r-alderaan", "lz:lz-bluebird", "lz:lz-hawk"]);
  });

  it("makes its Library record once, under one identity, then updates it", async () => {
    const { result } = render();
    act(() => result.current.setDoc((d) => ({ ...d, answers: { "1.b": { value: "Moderate" } } })));
    act(() => result.current.setDoc((d) => ({ ...d, answers: { ...d.answers, "2.b": { value: "500-1" } } })));
    expect(briefApi.createBrief).not.toHaveBeenCalled();
    await act(async () => { jest.advanceTimersByTime(800); });
    expect(briefApi.createBrief).toHaveBeenCalledTimes(1);
    const { clientUuid, data } = briefApi.createBrief.mock.calls[0][0];
    expect(clientUuid).toBeTruthy();
    expect(data.answers).toEqual({ "1.b": { value: "Moderate" }, "2.b": { value: "500-1" } });
    expect(JSON.parse(localStorage.getItem("ezpz.brief.7"))).toEqual({ id: 41, clientUuid });

    act(() => result.current.setDoc((d) => ({ ...d, answers: { ...d.answers, "3.a": { value: "Insert" } } })));
    await act(async () => { jest.advanceTimersByTime(800); });
    expect(briefApi.updateBrief).toHaveBeenCalledWith(41, expect.objectContaining({ data: expect.objectContaining({ answers: expect.objectContaining({ "3.a": { value: "Insert" } }) }) }));
    expect(briefApi.createBrief).toHaveBeenCalledTimes(1);
    expect(result.current.saveState).toBe("saved");
  });

  it("opens the brief this browser kept for the account", async () => {
    localStorage.setItem("ezpz.brief.7", JSON.stringify({ id: 41, clientUuid: "c-1" }));
    briefApi.getBrief.mockResolvedValue({ id: 41, data: { version: 1, template: { id: "hasty", version: 1 }, answers: { "8.a": { value: "12:45" } } } });
    const { result } = render();
    await waitFor(() => expect(result.current.hasDoc).toBe(true));
    expect(result.current.format.template.id).toBe("hasty");
    expect(briefApi.getBrief).toHaveBeenCalledWith(41);
  });

  it("asks for the weather at the departure, the arrival and each stop, at their times", async () => {
    render();
    await waitFor(() => expect(briefApi.weatherAt).toHaveBeenCalled());
    const asked = briefApi.weatherAt.mock.calls.map(([place]) => place);
    expect(asked[0]).toEqual({ lat: 33.9151, lon: -84.5163, at: expect.stringMatching(/^2026-10-16T\d\d:56:32\.000Z$/) });
    expect(asked).toHaveLength(2 + 5);
  });

  it("asks nothing while the brief is closed", () => {
    render(false);
    expect(briefApi.weatherAt).not.toHaveBeenCalled();
    expect(briefApi.getBrief).not.toHaveBeenCalled();
  });
});

describe("useBrief in a pack", () => {
  const items = OP_DK.lzs.map((lz) => ({ uuid: lz.id, kind: "lz", name: lz.diagram.name, data: lz.diagram }))
    .concat(OP_DK.routeSets.map((set) => ({ uuid: set.id, kind: "route", name: set.name, data: set.data })));
  const render = (packItems, edit = jest.fn(() => null), readOnly = false) => {
    const { result } = renderHook(() => useBrief({
      enabled: true, active: true, user: USER, pack: { name: "OP DK", items: packItems, edit, readOnly },
    }));
    return { result, edit };
  };

  it("makes the pack's brief item at the first change", () => {
    const { result, edit } = render(items);
    act(() => result.current.setDoc((d) => ({ ...d, answers: { "1.b": { value: "High" } } })));
    const [ops] = edit.mock.calls[0];
    expect(ops).toHaveLength(1);
    expect(ops[0]).toMatchObject({ type: "item.create", kind: "brief", name: "OP DK BRIEF", summary: 'Colin M. added the brief "OP DK BRIEF".' });
    expect(ops[0].item).toMatch(/^br-/);
    expect(ops[0].data.answers).toEqual({ "1.b": { value: "High" } });
    expect(briefApi.createBrief).not.toHaveBeenCalled();
  });

  it("sends a change to the brief item as the pack's operations, each with a sentence", () => {
    const brief = { uuid: "br-1", kind: "brief", name: "OP DK BRIEF", data: { version: 1, template: { id: "amb", version: 1 }, answers: {} } };
    const { result, edit } = render([...items, brief]);
    expect(result.current.hasDoc).toBe(true);
    act(() => result.current.setDoc((d) => ({ ...d, answers: { "2.b": { value: "500-1" } } })));
    const [ops] = edit.mock.calls[0];
    expect(ops.every((op) => op.item === "br-1" && op.summary === 'Colin M. changed the brief "OP DK BRIEF".')).toBe(true);
  });

  it("builds from every LZ/PZ and route set in the pack", () => {
    const { result } = render(items);
    expect(result.current.summary).toBe("5 LZ/PZs, 1 route set");
    expect(result.current.model.flow.filter((f) => f.kind === "stop")).toHaveLength(5);
  });
});
