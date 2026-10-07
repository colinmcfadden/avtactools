import fs from "fs";
import path from "path";

// jsdom applies no stylesheet, so how menus, dialogs and toasts stack can only be read from ui.css.
// A menu opened inside a dialog (a Library row's ⋯) must paint above it, and toasts above both.
const css = fs.readFileSync(path.join(__dirname, "ui.css"), "utf8");
const zIndexOf = (selector) => {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const rule = css.match(new RegExp(`(^|\\n)${escaped} \\{([^}]*)\\}`));
  const z = rule?.[2].match(/z-index:\s*(\d+)/);
  return z ? Number(z[1]) : NaN;
};

describe("how the shared layers stack", () => {
  it("puts a menu above a dialog and below the toasts", () => {
    const dialog = zIndexOf(".ui-dialog__backdrop");
    const menu = zIndexOf(".ui-menu");
    const toasts = zIndexOf(".ui-toasts");
    expect([dialog, menu, toasts].every(Number.isFinite)).toBe(true);
    expect(menu).toBeGreaterThan(dialog);
    expect(toasts).toBeGreaterThan(menu);
  });
});
