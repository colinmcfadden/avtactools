import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ToastProvider } from "./feature/ui/Toast";
import App from "./App";

/*
 * Undo as App wires it, from the top bar and the keyboard through to what the map is given. The map and
 * the left sidebar stand in for themselves: what matters is what they are handed and what they call.
 */

window.matchMedia ??= (query) => ({ matches: false, media: query, addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {} });
global.ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} };
window.scrollTo = () => {};

let map = null;
let controls = null;
vi.mock("./components/MapView", () => ({ default: (props) => { map = props; return null; } }));
vi.mock("./components/Controls", async (importOriginal) => ({
  ...(await importOriginal()),
  default: (props) => { controls = props; return null; },
}));
vi.mock("./feature/auth/AuthContext", () => ({
  useAuth: () => ({ user: { id: 1, email: "pilot@local.ezpz.test", name: "Test Pilot", is_admin: true, access_ok: true, features: {} }, isLoading: false, logout: () => {} }),
  AuthProvider: ({ children }) => children,
}));
vi.mock("./feature/auth/api", () => {
  const empty = () => Promise.resolve({ data: [] });
  const api = { get: vi.fn(empty), post: vi.fn(empty), put: vi.fn(empty), patch: vi.fn(empty), delete: vi.fn(empty), interceptors: { request: { use() {} }, response: { use() {} } }, defaults: {} };
  return { default: api, api };
});

const opened = async () => {
  render(<ToastProvider><App /></ToastProvider>);
  const [undo] = await screen.findAllByRole("button", { name: /undo/i });
  const [redo] = await screen.findAllByRole("button", { name: /redo/i });
  return { undo, redo };
};

const sketched = async (points = [[34.5, -84.1], [34.6, -84.2], [34.7, -84.3]]) => {
  act(() => controls.toggleRouteSketch());
  points.forEach(([lat, lon]) => act(() => map.addDraftPoint(lat, lon)));
  act(() => controls.toggleRouteSketch());
  fireEvent.click(await screen.findByRole("button", { name: "Finish route" }));
  await waitFor(() => expect(map.sketchedRoutes).toHaveLength(1));
};

const ctrl = (key, more = {}) => act(() => { fireEvent.keyDown(window, { key, ctrlKey: true, ...more }); });

describe("undo in the app", () => {
  it("has nothing to take back at first, and the keys do nothing", async () => {
    const { undo, redo } = await opened();
    expect([undo.disabled, redo.disabled]).toEqual([true, true]);
    ctrl("z");
    ctrl("z", { shiftKey: true });
    expect(map.sketchedRoutes).toEqual([]);
  });

  it("takes a sketch's last point back with the key while drawing", async () => {
    await opened();
    act(() => controls.toggleRouteSketch());
    act(() => map.addDraftPoint(34.5, -84.1));
    act(() => map.addDraftPoint(34.6, -84.2));
    ctrl("z");
    expect(map.draftPoints).toHaveLength(1);
  });

  it("takes back and makes again a route drawn, from the top bar, and says what it did", async () => {
    const { undo, redo } = await opened();
    await sketched();
    expect(undo.disabled).toBe(false);
    fireEvent.click(undo);
    expect(map.sketchedRoutes).toEqual([]);
    expect(await screen.findByText("Undid: Draw route")).toBeTruthy();
    expect(redo.disabled).toBe(false);
    fireEvent.click(redo);
    expect(map.sketchedRoutes).toHaveLength(1);
  });

  it("takes back a point moved on the map, by the keys, and redoes it", async () => {
    await opened();
    await sketched();
    const route = map.sketchedRoutes[0];
    const point = route.points[1];
    act(() => map.onUpdateSketchPointPosition(route.id, point.id, 35, -85));
    expect(map.sketchedRoutes[0].points[1]).toMatchObject({ lat: 35, lon: -85 });
    ctrl("z");
    expect(map.sketchedRoutes[0].points[1]).toMatchObject({ lat: point.lat, lon: point.lon });
    ctrl("y");
    expect(map.sketchedRoutes[0].points[1]).toMatchObject({ lat: 35, lon: -85 });
  });

  it("leaves the text being typed in a field to the field", async () => {
    await opened();
    await sketched();
    const field = document.createElement("input");
    document.body.appendChild(field);
    field.focus();
    fireEvent.keyDown(field, { key: "z", ctrlKey: true });
    expect(map.sketchedRoutes).toHaveLength(1);
    field.remove();
  });
});
