import React from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import RoutePlanSection from "./RoutePlanSection";

const amps = (id, lat, lon, name) => ({ id, lat, lon, kind: "amps", ptType: "turn", name, role: "waypoint" });
const threePoints = { id: "r1", color: "#f00", points: [amps("a", 34, -84, ".SP"), amps("b", 34.1, -84, ".CP1"), amps("c", 34.2, -84, ".RP")] };
const twoPoints = { id: "r1", color: "#f00", points: [amps("a", 34, -84, ".SP"), amps("b", 34.1, -84, ".RP")] };
const GRID = "16S GC 28864 55349";

const setup = (props = {}, route = threePoints) => {
  const addPoint = vi.fn(() => "new-id");
  const removePoint = vi.fn();
  const view = render(
    <RoutePlanSection
      route={route}
      addPoint={addPoint}
      removePoint={removePoint}
      updateRoutePlan={vi.fn()}
      updatePointPlanOverride={vi.fn()}
      setPointClock={vi.fn()}
      updatePointName={vi.fn()}
      refreshRouteElevations={vi.fn()}
      applyForecastWinds={vi.fn()}
      {...props}
    />,
  );
  return { addPoint, removePoint, ...view };
};

const field = () => screen.getByRole("combobox", { name: /^Add a point/ });
const addTyped = (text) => {
  fireEvent.focus(field());
  fireEvent.change(field(), { target: { value: text } });
  fireEvent.click(screen.getByRole("button", { name: "Add" }));
};

describe("RoutePlanSection: adding and deleting points", () => {
  it("adds a typed grid at the end of the route, with no name of its own, and stays open for the next", () => {
    const { addPoint } = setup();
    fireEvent.click(screen.getByRole("button", { name: "Add point" }));
    expect(screen.getByText("At the end of the route")).toBeInTheDocument();
    addTyped(GRID);
    expect(addPoint).toHaveBeenCalledWith("r1", null, expect.objectContaining({ name: "", lat: expect.any(Number) }));
    expect(field()).toHaveValue("");
  });

  it("adds after a chosen point, and goes on after the one it added", () => {
    const { addPoint } = setup();
    fireEvent.click(screen.getByRole("button", { name: "Add a point after .SP" }));
    expect(screen.getByText("After .SP")).toBeInTheDocument();
    addTyped(GRID);
    expect(addPoint.mock.calls[0][1]).toBe("a");
  });

  it("names a point after the local point chosen, with its charted elevation", () => {
    const localPoints = [{ name: "hawk", lat: 34.6, lon: -84.2, elevationFt: 1730, setName: "NORTH" }];
    const { addPoint } = setup({ localPoints });
    fireEvent.click(screen.getByRole("button", { name: "Add point" }));
    fireEvent.focus(field());
    fireEvent.change(field(), { target: { value: "haw" } });
    fireEvent.click(screen.getByRole("option", { name: /hawk/i }));
    expect(addPoint).toHaveBeenCalledWith("r1", null, expect.objectContaining({ name: "HAWK", chartElevationFt: 1730 }));
  });

  it("says why a point could not be added", () => {
    const addPoint = vi.fn(() => { throw new Error("no leg near it"); });
    setup({ addPoint });
    fireEvent.click(screen.getByRole("button", { name: "Add point" }));
    addTyped(GRID);
    expect(screen.getByText("The point could not be added: no leg near it")).toBeInTheDocument();
  });

  it("closes on the second Escape, the list taking the first", () => {
    setup({ localPoints: [{ name: "HAWK", lat: 34.6, lon: -84.2, setName: "N" }] });
    fireEvent.click(screen.getByRole("button", { name: "Add point" }));
    fireEvent.focus(field());
    fireEvent.change(field(), { target: { value: "haw" } });
    fireEvent.keyDown(field(), { key: "Escape" });
    expect(field()).toBeInTheDocument();
    fireEvent.keyDown(field(), { key: "Escape" });
    expect(screen.queryByRole("combobox", { name: /^Add a point/ })).not.toBeInTheDocument();
  });

  it("deletes a point", () => {
    const { removePoint } = setup();
    fireEvent.click(screen.getByRole("button", { name: "Delete .CP1" }));
    expect(removePoint).toHaveBeenCalledWith("r1", "b");
  });

  it("says why a point must stay, and leaves it", () => {
    const { removePoint } = setup({}, twoPoints);
    const button = screen.getByRole("button", { name: "Delete .SP" });
    expect(button).toHaveAttribute("aria-disabled", "true");
    fireEvent.click(button);
    expect(removePoint).not.toHaveBeenCalled();
    expect(screen.getByRole("status")).toHaveTextContent("A route needs at least two points.");
  });

  it("closes the form when the point it was adding after has gone", () => {
    const { rerender } = setup();
    fireEvent.click(screen.getByRole("button", { name: "Add a point after .SP" }));
    expect(field()).toBeInTheDocument();
    const without = { ...threePoints, points: threePoints.points.slice(1) };
    rerender(<RoutePlanSection route={without} addPoint={vi.fn()} removePoint={vi.fn()} updateRoutePlan={vi.fn()} updatePointPlanOverride={vi.fn()} setPointClock={vi.fn()} updatePointName={vi.fn()} refreshRouteElevations={vi.fn()} applyForecastWinds={vi.fn()} />);
    expect(screen.queryByRole("combobox", { name: /^Add a point/ })).not.toBeInTheDocument();
  });

  it("offers neither in a read-only pack", () => {
    setup({ readOnly: true });
    expect(screen.queryByRole("button", { name: "Add point" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Delete/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Add a point after/ })).not.toBeInTheDocument();
  });

  it("on a mission adds only from the foot, says so, and has no delete", () => {
    setup({ addsOnNearestLeg: true, removePoint: undefined });
    expect(screen.queryByRole("button", { name: /^Add a point after/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Delete/ })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Add point" }));
    expect(screen.getByText(/nearest leg.*can't be undone/)).toBeInTheDocument();
  });
});
