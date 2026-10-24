import fs from "fs";
import path from "path";

// jsdom applies no stylesheet, so how the layers stack can only be read from the CSS. They all share one
// stacking context (the app container sets no z-index; menus, dialogs and toasts are portaled to the
// body), so comparing the numbers is comparing what paints over what.
const SRC = path.join(__dirname, "..", "..");
const read = (file) => fs.readFileSync(path.join(SRC, file), "utf8");

// Every rule of a stylesheet, a media query's included, with the z-index values it sets.
const rules = (css) =>
  [...css.replace(/\/\*[\s\S]*?\*\//g, "").matchAll(/([^{}]+)\{([^{}]*)\}/g)].map(([, head, body]) => ({
    selectors: head.split(",").map((s) => s.trim()),
    zIndices: [...body.matchAll(/z-index:\s*(-?\d+)/g)].map((z) => Number(z[1])),
  }));
const everyZIndex = (css, selector) =>
  rules(css).filter((r) => r.selectors.includes(selector)).flatMap((r) => r.zIndices);
const zIndexOf = (css, selector) => {
  const set = rules(css).filter((r) => r.selectors.includes(selector) && r.zIndices.length);
  return set.length === 1 ? set[0].zIndices.at(-1) : NaN;
};

const sourceFiles = (dir) =>
  fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const file = path.join(dir, entry.name);
    return entry.isDirectory() ? sourceFiles(file) : [file];
  });

const ui = read("feature/ui/ui.css");
const app = read("App.css");
const dialog = zIndexOf(ui, ".ui-dialog__backdrop");
const windows = zIndexOf(read("feature/export/ExportModal.css"), ".export-modal-container");
const leafletTop = Math.max(
  ...rules(fs.readFileSync(require.resolve("leaflet/dist/leaflet.css"), "utf8")).flatMap((r) => r.zIndices),
);

describe("how the shared layers stack", () => {
  // A menu opened inside a dialog (a Library row's ⋯) must paint above it, and toasts above both.
  it("puts a menu above a dialog and below the toasts", () => {
    const menu = zIndexOf(ui, ".ui-menu");
    const toasts = zIndexOf(ui, ".ui-toasts");
    expect([dialog, menu, toasts].every(Number.isFinite)).toBe(true);
    expect(menu).toBeGreaterThan(dialog);
    expect(toasts).toBeGreaterThan(menu);
  });

  // A planning window left open (aircraft profiles, the unit builder, a threat, an export, the 3D view)
  // floats over the map and the frame, or it would open behind them, but never over a dialog, or the
  // menus and toasts above it: a top-bar menu opened over a window has to be clickable.
  it("keeps the floating windows over the map and the frame, under the dialogs", () => {
    const shell = read("feature/shell/shell.css");
    const frame = [
      zIndexOf(shell, ".shell-topbar"),
      zIndexOf(shell, ".shell-dock"),
      zIndexOf(app, ".sidebar-collapse-toggle"),
      // The left panel; on a phone it is a full-screen menu a window can be opened from.
      ...everyZIndex(app, ".sidebar"),
      ...everyZIndex(read("index.css"), ".sidebar"),
    ];
    expect([windows, dialog, leafletTop, ...frame].every(Number.isFinite)).toBe(true);
    expect(frame.length).toBeGreaterThanOrEqual(4);
    expect(windows).toBeGreaterThan(Math.max(leafletTop, ...frame));
    expect(windows).toBeLessThan(dialog);
  });

  it("gives every floating window that one layer", () => {
    const files = sourceFiles(SRC).filter((file) => !/\.test\.jsx?$/.test(file));
    const windowFiles = files.filter(
      (file) => /\.jsx?$/.test(file) && fs.readFileSync(file, "utf8").includes("export-modal-container"),
    );
    expect(windowFiles.length).toBeGreaterThanOrEqual(7);
    for (const file of windowFiles) expect([file, /zIndex/.test(fs.readFileSync(file, "utf8"))]).toEqual([file, false]);

    // A rule whose subject is a window (`.lz3d-window`, `.export-modal-container.glass-panel`) sets no other.
    const subject = (selector) => selector.split(/[\s>+~]+/).at(-1);
    const isWindow = (selector) => /\.(export-modal-container|lz3d-window)\b/.test(subject(selector));
    const layers = files
      .filter((file) => file.endsWith(".css"))
      .flatMap((file) => rules(fs.readFileSync(file, "utf8")))
      .filter((r) => r.selectors.some(isWindow))
      .flatMap((r) => r.zIndices);
    expect(layers).toEqual([windows]);
  });

  // The separation alerts float over the map; a dialog, or a window dragged over them, is above.
  it("keeps the proximity alerts over the map, under the windows and the dialogs", () => {
    const alerts = zIndexOf(app, ".alert-queue");
    expect(Number.isFinite(alerts)).toBe(true);
    expect(alerts).toBeGreaterThan(leafletTop);
    expect(alerts).toBeLessThan(windows);
    expect(alerts).toBeLessThan(dialog);
  });
});
