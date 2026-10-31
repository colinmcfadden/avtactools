import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import Icon from "../feature/ui/Icon";
import { MOVE_ICON_PATHS, ROTATE_ICON_PATHS, mapObjectControlMarkup } from "./mapObjectControls";

const pathsOf = (name) => renderToStaticMarkup(<Icon name={name} />).replace(/^<svg[^>]*>|<\/svg>$/g, "");

describe("the map's move and rotate handles", () => {
  it("draw the redesign's own move and rotate icons", () => {
    expect(MOVE_ICON_PATHS).toBe(pathsOf("move"));
    expect(ROTATE_ICON_PATHS).toBe(pathsOf("rotateCw"));
  });

  it("are named for what they do", () => {
    const html = mapObjectControlMarkup({ type: "rotate", title: "Drag to rotate helicopter" });
    expect(html).toContain('aria-label="Drag to rotate helicopter"');
    expect(html).toContain(ROTATE_ICON_PATHS);
    expect(mapObjectControlMarkup({ type: "drag", title: "Move" })).toContain(MOVE_ICON_PATHS);
  });
});
