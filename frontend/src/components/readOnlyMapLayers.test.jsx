import { act, fireEvent, render as rtlRender, screen } from "@testing-library/react";
import L from "leaflet";
import React from "react";
import Doghouse from "../feature/doghouses/Doghouse";
import GoAroundMarker from "../feature/goAround/GoAround";
import Helicopter from "../feature/helicopters/Helicopter";
import MsnxRouteLayer from "../feature/msnxImport/MsnxRouteLayer";
import PZMarker from "../feature/pzMarker/PZMarker";
import SectorMarker from "../feature/sectorsOfFire/SectorMarker";
import UnitMarker from "../feature/unit/UnitMarker";

/*
 * In a read-only Mission Pack (finished, or a viewer) App hands the map's layers no edit handlers.
 * Each layer must then offer no drag, delete or edit, rather than one the pack quietly puts back.
 *
 * react-leaflet is an ES module Jest does not transform, so it is stood in for: useMap gives a real
 * Leaflet map for the layers that draw themselves, and the declarative pieces render plain divs
 * carrying the props the layers gave them.
 */
jest.mock("react-leaflet", () => {
  const ReactMock = require("react");
  const holder = { map: null };
  const contextMenu = (props) => () =>
    props.eventHandlers?.contextmenu?.({
      latlng: { lat: 34, lng: -84 },
      originalEvent: { clientX: 1, clientY: 1, preventDefault() {}, stopPropagation() {} },
      stopPropagation() {},
    });
  const Piece = (testid) =>
    ReactMock.forwardRef((props, _ref) =>
      ReactMock.createElement("div", {
        "data-testid": testid,
        "data-draggable": String(Boolean(props.draggable)),
        "data-html": props.icon?.options?.html ?? "",
        onContextMenu: contextMenu(props),
      }),
    );
  return {
    __holder: holder,
    useMap: () => holder.map,
    useMapEvents: () => holder.map,
    Marker: Piece("marker"),
    Polygon: Piece("polygon"),
    Polyline: Piece("polyline"),
    Tooltip: () => null,
    Popup: () => null,
  };
});

const { __holder: holder } = jest.requireMock("react-leaflet");

let container;
beforeEach(() => {
  jest.useFakeTimers();
  container = document.createElement("div");
  document.body.appendChild(container);
  holder.map = L.map(container).setView([34.5, -84.2], 16);
  jest.spyOn(window, "confirm").mockReturnValue(true);
});
afterEach(() => {
  holder.map.remove();
  container.remove();
  jest.restoreAllMocks();
  jest.useRealTimers();
});

// The layers that draw themselves bind their handlers again a moment after each redraw.
const render = (ui) => {
  const result = rtlRender(ui);
  act(() => jest.advanceTimersByTime(200));
  return result;
};

const iconOf = (selector) => container.querySelector(selector);

describe("a helicopter", () => {
  const asset = { id: 1, type: "helo", lat: 34.5, lon: -84.2, rotation: 90 };

  it("in a read-only pack has no move or rotate control, and a right-click does not delete it", () => {
    const deleteAsset = jest.fn();
    render(<Helicopter asset={asset} allAssets={[asset]} profiles={[]} />);
    expect(iconOf(".dh-move")).toBeNull();
    expect(iconOf(".dh-rotate")).toBeNull();
    fireEvent.contextMenu(iconOf(".helo-body-wrapper"));
    expect(window.confirm).not.toHaveBeenCalled();
    expect(deleteAsset).not.toHaveBeenCalled();
  });

  it("can be moved, turned and deleted otherwise", () => {
    const deleteAsset = jest.fn();
    render(<Helicopter asset={asset} allAssets={[asset]} profiles={[]} updateAsset={() => {}} deleteAsset={deleteAsset} />);
    expect(iconOf(".dh-move")).not.toBeNull();
    expect(iconOf(".dh-rotate")).not.toBeNull();
    fireEvent.contextMenu(iconOf(".helo-body-wrapper"));
    expect(deleteAsset).toHaveBeenCalledWith(1);
  });
});

describe("a doghouse", () => {
  const data = { id: 3, lat: 34.5, lon: -84.2, heading: "090°", time: "1+30", dist: "3", airspeed: "100", id_val: "A" };

  it("in a read-only pack has no move or rotate control, and its fields are not edit fields", () => {
    render(<Doghouse data={data} />);
    expect(iconOf(".dh-move")).toBeNull();
    expect(iconOf(".dh-rotate")).toBeNull();
    const field = iconOf('.dh-input[data-type="heading"]');
    expect(field.style.cursor).toBe("default");
    expect(field.onclick).toBeNull();
  });

  it("can be moved, turned and typed in otherwise", () => {
    render(<Doghouse data={data} updateDoghouse={() => {}} />);
    expect(iconOf(".dh-move")).not.toBeNull();
    const field = iconOf('.dh-input[data-type="heading"]');
    expect(field.style.cursor).toBe("text");
    expect(typeof field.onclick).toBe("function");
  });
});

describe("a go-around", () => {
  const data = { id: 4, lat: 34.5, lon: -84.2, rotation: 0, direction: "right" };

  it("in a read-only pack has no move or rotate control, and a right-click does not delete it", () => {
    render(<GoAroundMarker data={data} />);
    expect(iconOf(".dh-move")).toBeNull();
    expect(iconOf(".dh-rotate")).toBeNull();
    fireEvent.contextMenu(iconOf(".ga-body-wrapper"));
    expect(window.confirm).not.toHaveBeenCalled();
  });

  it("can be moved and deleted otherwise", () => {
    const deleteGoAround = jest.fn();
    render(<GoAroundMarker data={data} updateGoAround={() => {}} deleteGoAround={deleteGoAround} />);
    expect(iconOf(".dh-move")).not.toBeNull();
    fireEvent.contextMenu(iconOf(".ga-body-wrapper"));
    expect(deleteGoAround).toHaveBeenCalledWith(4);
  });
});

describe("a PZ marker", () => {
  const data = { id: 5, lat: 34.5, lon: -84.2, tipLat: 34.5, tipLon: -84.21 };

  it("in a read-only pack cannot be dragged or aimed", () => {
    render(<PZMarker data={data} />);
    expect(iconOf(".pz-container")).not.toHaveClass("leaflet-marker-draggable");
    expect(iconOf(".pz-tip-wrapper")).not.toHaveClass("leaflet-marker-draggable");
    expect(iconOf(".pz-move-control")).toBeNull();
  });

  it("can be dragged and aimed otherwise", () => {
    render(<PZMarker data={data} updatePZMarker={() => {}} deletePZMarker={() => {}} />);
    expect(iconOf(".pz-container")).toHaveClass("leaflet-marker-draggable");
    expect(iconOf(".pz-tip-wrapper")).toHaveClass("leaflet-marker-draggable");
    expect(iconOf(".pz-move-control")).not.toBeNull();
  });
});

describe("a unit", () => {
  const data = { id: 6, lat: 34.5, lon: -84.2, path: "/units/infantry.svg" };

  it("in a read-only pack cannot be dragged or deleted", () => {
    render(<UnitMarker data={data} />);
    const marker = screen.getByTestId("marker");
    expect(marker).toHaveAttribute("data-draggable", "false");
    expect(marker.getAttribute("data-html")).not.toContain("unit-object-controls");
    fireEvent.contextMenu(marker);
    expect(window.confirm).not.toHaveBeenCalled();
  });

  it("can be dragged and deleted otherwise", () => {
    const deleteUnit = jest.fn();
    render(<UnitMarker data={data} updateUnitPosition={() => {}} deleteUnit={deleteUnit} />);
    const marker = screen.getByTestId("marker");
    expect(marker).toHaveAttribute("data-draggable", "true");
    fireEvent.contextMenu(marker);
    expect(deleteUnit).toHaveBeenCalledWith(6);
  });
});

describe("a sector of fire", () => {
  const data = {
    id: 7,
    points: [
      { lat: 34.5, lng: -84.2 },
      { lat: 34.51, lng: -84.2 },
      { lat: 34.5, lng: -84.21 },
    ],
  };

  it("in a read-only pack has no handles, and a right-click does not delete it", () => {
    render(<SectorMarker data={data} />);
    expect(screen.queryAllByTestId("marker")).toHaveLength(0);
    fireEvent.contextMenu(screen.getByTestId("polygon"));
    expect(window.confirm).not.toHaveBeenCalled();
  });

  it("has a move handle and a handle per corner otherwise", () => {
    render(<SectorMarker data={data} updateSectorPoint={() => {}} moveSector={() => {}} deleteSector={() => {}} />);
    expect(screen.queryAllByTestId("marker")).toHaveLength(4);
  });
});

describe("a route's points", () => {
  const routes = [
    {
      id: "sketch-1",
      name: "ROUTE 1",
      color: "#f00",
      points: [
        { id: "p1", name: "SP", kind: "amps", ptType: "turn", lat: 34.5, lon: -84.2 },
        { id: "p2", name: "RP", kind: "amps", ptType: "ip", lat: 34.51, lon: -84.21 },
      ],
    },
  ];

  it("in a read-only pack cannot be dragged, and the line offers no point to insert", () => {
    render(<MsnxRouteLayer routes={routes} />);
    screen.getAllByTestId("marker").forEach((marker) => expect(marker).toHaveAttribute("data-draggable", "false"));
    // Without a handler the right-click is left to the map's own menu (before, this threw).
    fireEvent.contextMenu(screen.getByTestId("polyline"));
  });

  it("can be dragged, and the line offers a point to insert, otherwise", () => {
    const onInsertPoint = jest.fn();
    render(<MsnxRouteLayer routes={routes} onUpdatePosition={() => {}} onInsertPoint={onInsertPoint} />);
    screen.getAllByTestId("marker").forEach((marker) => expect(marker).toHaveAttribute("data-draggable", "true"));
    fireEvent.contextMenu(screen.getByTestId("polyline"));
    expect(onInsertPoint).toHaveBeenCalledWith("sketch-1", 34, -84, 1, 1);
  });
});
