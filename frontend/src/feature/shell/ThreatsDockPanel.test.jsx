import { render, screen } from "@testing-library/react";
import React from "react";
import ThreatsDockPanel from "./ThreatsDockPanel";

const noop = () => {};

describe("the Threats panel", () => {
  it("makes Add threat the primary action and Import .ths the secondary one", () => {
    render(
      <ThreatsDockPanel
        threats={[]}
        onAdd={noop}
        onImport={noop}
        onEdit={noop}
        onRemove={noop}
        onRemoveAll={noop}
        onToggleVisibility={noop}
        onExportThs={noop}
        onExportKmz={noop}
      />,
    );
    expect(screen.getByRole("button", { name: "Add threat" })).toHaveClass("ui-btn--primary");
    expect(screen.getByRole("button", { name: "Import .ths" })).not.toHaveClass("ui-btn--primary");
  });
});
