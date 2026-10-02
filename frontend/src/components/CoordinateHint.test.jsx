import React from "react";
import { render, screen } from "@testing-library/react";
import CoordinateHint from "./CoordinateHint";
import { formatMGRS } from "../utils/Helpers";

describe("CoordinateHint", () => {
  it("confirms a recognised coordinate", () => {
    render(<CoordinateHint value="34.5457, -84.1234" />);
    expect(screen.getByText("decimal degrees")).toBeInTheDocument();
    expect(screen.getByText("34.54570, -84.12340")).toBeInTheDocument();
  });

  it("names the notation it recognised", () => {
    render(<CoordinateHint value="34°32.740'N 084°07.407'W" />);
    expect(screen.getByText("degrees/decimal minutes")).toBeInTheDocument();
  });

  it.each([
    ["16S GC 28864 55349", "a grid"],
    ["", "an empty field"],
    ["34.5", "a half-typed value"],
    ["Dahlonega", "a place name"],
  ])("stays silent for %s (%s)", (value) => {
    const { container } = render(<CoordinateHint value={value} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("survives a missing value", () => {
    expect(() => render(<CoordinateHint />)).not.toThrow();
  });
});

describe("formatMGRS leaves coordinates alone", () => {
  // The mobile field reformats every keystroke. Before this, pasting a
  // coordinate stripped its punctuation — "34.5, -84.2" became "345842".
  it.each([
    "34.5457, -84.1234",
    "34.5, -84.2",
    "N34.5457 W084.1234",
    "34°32.740'N 084°07.407'W",
    "34.5457",          // mid-paste, not yet a pair
    "34.5457, -",       // mid-typing
    "-84",
  ])("passes %s through untouched", (text) => {
    expect(formatMGRS(text)).toBe(text);
  });

  it("still formats a grid as it is typed", () => {
    expect(formatMGRS("16sgc2886455349")).toBe("16S GC 28864 55349");
    expect(formatMGRS("16sgc")).toBe("16S GC");
  });

  it("still handles an empty field", () => {
    expect(formatMGRS("")).toBe("");
  });
});
