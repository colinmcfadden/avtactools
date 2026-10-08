import { fireEvent, render, screen } from "@testing-library/react";
import React from "react";
import MobileQuickAccess from "./MobileQuickAccess";

const renderQuickAccess = (props = {}) =>
  render(
    <MobileQuickAccess
      features={{ routes: false }}
      addHelo={() => {}}
      enableExportMode={() => {}}
      onDownloadClick={() => {}}
      exportBox={[[34, -84], [34.01, -83.99]]}
      isExporting={false}
      exportProgress={0}
      diagramStatus="analyzed"
      {...props}
    />,
  );

describe("the phone's quick access", () => {
  it("exports an analysed LZ/PZ that may not be changed (a finished pack), while its tools stay off", () => {
    const enableExportMode = jest.fn();
    const onDownloadClick = jest.fn();
    renderQuickAccess({ enableExportMode, onDownloadClick, canUseDiagramTools: false, canSaveDiagram: false, canExport: true });
    expect(screen.getByAltText("Helo").closest("button")).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: /Capture/ }));
    expect(enableExportMode).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: /Export/ }));
    expect(onDownloadClick).toHaveBeenCalledTimes(1);
  });

  it("does not export what is not analysed", () => {
    renderQuickAccess({ diagramStatus: "targeted", canUseDiagramTools: false, canSaveDiagram: false, canExport: false });
    expect(screen.getByRole("button", { name: /Capture/ })).toBeDisabled();
    expect(screen.getByRole("button", { name: /Export/ })).toBeDisabled();
  });
});
