import { render, screen } from "@testing-library/react";
import RoutePlanSection from "./RoutePlanSection";
import { defaultRoutePlan } from "./routeCalc";

// A route as a mission pack can hold it. No operation deletes a key, so one that went is sent as null: the ground of a point moved off it is null
// in `elevations`, and a point whose overrides went is null in `perPoint`. Each is none.
const route = (elevations, perPoint = {}) => ({
  id: "sketch-1",
  name: "RED 1",
  color: "#FF453A",
  plan: { ...defaultRoutePlan(), perPoint },
  elevations,
  points: [
    { id: "p1", lat: 34.5, lon: -84.1, kind: "amps", ptType: "target", name: ".SP", role: "start" },
    { id: "p2", lat: 34.52, lon: -84.12, kind: "amps", ptType: "turn", name: ".CP1", role: "waypoint" },
    { id: "p3", lat: 34.55, lon: -84.15, kind: "amps", ptType: "target", name: ".RP", role: "waypoint" },
  ],
});

const noop = () => {};
const plan = (r) =>
  render(
    <RoutePlanSection
      route={r}
      updateRoutePlan={noop}
      updatePointPlanOverride={noop}
      setPointClock={noop}
      updatePointName={noop}
      refreshRouteElevations={noop}
      applyForecastWinds={noop}
    />,
  );

const elevationsButton = () => screen.getByRole("button", { name: /Get elevations/ });

describe("a route's plan with what a mission pack left null", () => {
  it("does not offer to fetch again the ground it has none of", () => {
    plan(route({ p1: null, p3: null }));
    expect(elevationsButton().getAttribute("title")).not.toMatch(/again/);
  });

  it("offers to fetch again once one point has its ground", () => {
    plan(route({ p1: 1000, p3: null }));
    expect(elevationsButton().getAttribute("title")).toMatch(/^Fetch the ground elevations again\./);
  });

  it("reads a point whose values went as one with none, and times the route from its clock", () => {
    plan(route({}, { p1: null, p2: { clock: null, altitude: { value: 300, ref: "msl" } }, p3: { clock: "12:30:00" } }));
    expect(screen.getAllByDisplayValue("300")).toHaveLength(1);           // .CP1's own altitude; the others fly the route's
    expect(screen.getByText("12:30:00")).toBeInTheDocument();
  });
});
