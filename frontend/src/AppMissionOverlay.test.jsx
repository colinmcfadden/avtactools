import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ToastProvider } from "./feature/ui/Toast";
import App from "./App";

/*
 * The mission overlay as App wires it: while its dialog is open the open LZ/PZ is taken off the map, since the overlay is
 * the routes, control measures and threats, never an LZ/PZ. The map stands in for itself: what matters is what it is given.
 */

window.matchMedia ??= (query) => ({ matches: false, media: query, addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {} });
global.ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} };
window.scrollTo = () => {};

let map = null;
vi.mock("./components/MapView", () => ({ default: (props) => { map = props; return null; } }));
vi.mock("./feature/auth/AuthContext", () => ({
  useAuth: () => ({ user: { id: 1, email: "pilot@local.ezpz.test", name: "Test Pilot", is_admin: true, access_ok: true, features: {} }, isLoading: false, logout: () => {} }),
  AuthProvider: ({ children }) => children,
}));
vi.mock("./feature/auth/api", () => {
  const empty = () => Promise.resolve({ data: [] });
  const api = { get: vi.fn(empty), post: vi.fn(empty), put: vi.fn(empty), patch: vi.fn(empty), delete: vi.fn(empty), interceptors: { request: { use() {} }, response: { use() {} } }, defaults: {} };
  return { default: api, api };
});

describe("the mission overlay in the app", () => {
  it("takes the open LZ/PZ off the map while its dialog is open", async () => {
    render(<ToastProvider><App /></ToastProvider>);
    await waitFor(() => expect(map).not.toBeNull());
    expect(map.lzShown).toBe(true);
    const [exportButton] = await screen.findAllByRole("button", { name: /^Export/ });
    fireEvent.click(exportButton);
    fireEvent.click(await screen.findByText("Mission overlay"));
    await waitFor(() => expect(map.lzShown).toBe(false));
  });
});
