import { render, screen } from "@testing-library/react";
import fs from "fs";
import path from "path";
import React from "react";
import RoutePlanSection from "../msnxImport/RoutePlanSection";
import ThreatsDockPanel from "./ThreatsDockPanel";
import TopBar from "./TopBar";

// docs/MENU_REDESIGN.md §3 keeps text at 11 px or more. jsdom applies no stylesheet, so the
// redesign's own sheets are read, and the components are checked for sizes set inline over them.

const SHEETS = ["ui/ui.css", "shell/shell.css", "missionPacks/ui/packs.css", "library/library.css", "imports/imports.css"];
// Initials in an 18 or 14 px circle beside the name written out; ui.css says why.
const EXEMPT = [".ui-avatar--sm", ".ui-avatar--xs"];

const fontSizes = (file) => {
  const css = fs.readFileSync(path.join(__dirname, "..", file), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
  const found = [];
  // Innermost blocks only, so a rule inside @media is read with its own selector.
  for (const [, selector, body] of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    for (const [, value] of body.matchAll(/font-size:\s*([^;]+)/g)) {
      found.push({ file, selector: selector.trim(), value: value.trim() });
    }
  }
  return found;
};

describe("the redesign's stylesheets", () => {
  const sizes = SHEETS.flatMap(fontSizes);

  it("are all read", () => {
    expect(sizes.length).toBeGreaterThan(50);
  });

  it("set no text under 11 px", () => {
    const small = sizes.filter(({ selector, value }) => {
      if (EXEMPT.includes(selector)) return false;
      const px = value.match(/^(\d+(?:\.\d+)?)px$/);
      // A size in another unit cannot be judged here; say so rather than pass it.
      return !px || Number(px[1]) < 11;
    });
    expect(small).toEqual([]);
  });
});

describe("text sized inline", () => {
  it("leaves the threats' ForeFlight export at the buttons' size, with a label that fits", () => {
    render(
      <ThreatsDockPanel
        threats={[{ id: "t1", name: "SA-8", visible: true, radars: [] }]}
        onAdd={() => {}}
        onImport={() => {}}
        onEdit={() => {}}
        onRemove={() => {}}
        onRemoveAll={() => {}}
        onToggleVisibility={() => {}}
        onExportThs={() => {}}
        onExportKmz={() => {}}
      />,
    );
    const button = screen.getByRole("button", { name: "Export for ForeFlight / ATAK" });
    expect(button.style.fontSize).toBe("");
  });

  it("leaves the workspace switcher's Pack chip at the chips' size", () => {
    render(<TopBar pack={{ name: "OP DARK KNIGHT", status: "active" }} renderSwitcher={() => null} />);
    expect(screen.getByText("Pack").style.fontSize).toBe("");
  });
});

describe("the route plan editor's labels", () => {
  const point = (id, lat, name) => ({ id, lat, lon: -84, kind: "amps", ptType: "turn", name });
  const route = { id: "r1", name: "ROUTE 1", color: "#e5533d", points: [point("a", 34, ".SP"), point("b", 34.1, ".CP1")] };

  it("put one label over the wind's two boxes, and name the speed box for a screen reader", () => {
    render(
      <RoutePlanSection
        route={route}
        updateRoutePlan={() => {}}
        updatePointPlanOverride={() => {}}
        setPointClock={() => {}}
        updatePointName={() => {}}
        refreshRouteElevations={() => {}}
        applyForecastWinds={() => {}}
      />,
    );
    // A "KT" label of its own ran into WIND °T once both were 11 px.
    expect(screen.queryByText("KT")).toBeNull();
    expect(screen.getByLabelText("Wind °T")).toBeInTheDocument();
    expect(screen.getByRole("spinbutton", { name: "Wind speed at .CP1, knots" })).toBeInTheDocument();
  });
});
