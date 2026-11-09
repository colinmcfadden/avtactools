import { act, render, screen } from "@testing-library/react";
import React from "react";
import { MapContainer, useMap } from "react-leaflet";
import VfrAttribution from "./VfrAttribution";
import { keepInCapture } from "../export/captureFilter";
import { ARCGIS_WORDS, liveBuildOf } from "./vfrCharts";

const { readFixture } = require("../../contracts/readFixture");

const COVERAGE = readFixture("charts/coverage.json");
const EDITION = "29 Oct – 23 Dec 2026";
const LIVE = liveBuildOf({
  charts: {
    vfr: {
      build: "2026-10-29.1",
      tiles: "/t/{z}/{x}/{y}.webp",
      coverage: "/c.json",
      tileSize: 512,
      minZoom: 5,
      maxZoom: 14,
      effective: "2026-10-29T09:01Z",
      expires: "2026-12-24T09:01Z",
      bounds: [-88, 32, -72, 40],
      fallback: [],
    },
  },
});

let map;
const Grab = () => {
  map = useMap();
  return null;
};

const opened = (charts, center = [33.5, -87], zoom = 10) =>
  render(
    <MapContainer center={center} zoom={zoom} zoomAnimation={false} fadeAnimation={false} style={{ width: 400, height: 400 }}>
      <Grab />
      <VfrAttribution charts={{ known: true, live: LIVE, coverage: COVERAGE, tileFailed: () => {}, ...charts }} />
    </MapContainer>,
  );

afterEach(() => vi.useRealTimers());

describe("what the VFR map says of its charts", () => {
  beforeEach(() => vi.useFakeTimers({ now: Date.parse("2026-11-05T12:00:00Z"), toFake: ["setTimeout", "clearTimeout", "Date"] }));

  it("names the chart under the map", () => {
    opened();
    expect(screen.getByText(`FAA Atlanta Sectional, ${EDITION}`)).toBeInTheDocument();
  });

  it("says nothing until the server has said whose charts they are", () => {
    const { container } = opened({ known: false, live: null, coverage: null });
    expect(container.querySelector(".vfr-attribution")).toBeEmptyDOMElement();
  });

  it("names ArcGIS's service where it draws the map", () => {
    opened({ live: null, coverage: null });
    expect(screen.getByText(ARCGIS_WORDS)).toBeInTheDocument();
  });

  it("follows the map", () => {
    opened();
    act(() => map.setView([33.75, -84.4], 13, { animate: false }));
    expect(screen.getByText(`FAA Atlanta TAC, ${EDITION}`)).toBeInTheDocument();
    expect(screen.queryByText(`FAA Atlanta Sectional, ${EDITION}`)).toBeNull();
  });

  it("says quietly when a chart is enlarged", () => {
    const { container } = opened({}, [33.5, -87], 16);
    expect(container.querySelector(".vfr-attribution__enlarged")).toHaveTextContent("Sectional enlarged 16× — no finer chart here");
  });

  it("turns red at the expiry instant with nobody touching the map", () => {
    const { container } = opened();
    expect(container.querySelector(".vfr-attribution__expired")).toBeNull();
    // Seven weeks is past what one timer can hold, so it is waited for in two.
    const longest = 2 ** 31 - 1;
    act(() => vi.advanceTimersByTime(longest));
    expect(container.querySelector(".vfr-attribution__expired")).toBeNull();
    act(() => vi.advanceTimersByTime(Date.parse("2026-12-24T09:01:00Z") - Date.now() - 1));
    expect(container.querySelector(".vfr-attribution__expired")).toBeNull();
    act(() => vi.advanceTimersByTime(2));
    expect(container.querySelector(".vfr-attribution__expired")).toHaveTextContent("FAA Atlanta Sectional · Expired 23 Dec 2026 — not current");
  });

  it("sits with the map's attribution, and so stays off an LZ card", () => {
    const { container } = opened();
    const box = container.querySelector(".vfr-attribution");
    expect(box.closest(".leaflet-bottom.leaflet-right")).not.toBeNull();
    const controls = box.closest(".leaflet-control-container");
    expect(keepInCapture(controls)).toBe(false);
  });

  it("goes with the VFR map", () => {
    const { container, unmount } = opened();
    expect(container.querySelector(".vfr-attribution")).not.toBeNull();
    unmount();
    expect(document.querySelector(".vfr-attribution")).toBeNull();
  });
});
