import { fireEvent, render, screen } from "@testing-library/react";
import React from "react";
import { ToastProvider } from "../ui/Toast";
import LibraryDialog from "./LibraryDialog";

jest.mock("../auth/api", () => ({ __esModule: true, default: { get: jest.fn(), put: jest.fn(), post: jest.fn() } }));

const source = (patch = {}) => ({
  entries: [],
  loading: false,
  error: null,
  openIds: new Set(),
  refresh: jest.fn(),
  onOpen: jest.fn(),
  onDelete: jest.fn(),
  ...patch,
});

const show = (sources) =>
  render(
    <ToastProvider>
      <LibraryDialog sources={{ lz: source(), routes: source(), points: source(), ...sources }} onClose={() => {}} />
    </ToastProvider>,
  );

describe("the Library", () => {
  it("says a list that failed to load could not be loaded, never that nothing is saved, and tries again", () => {
    const lz = source({ error: new Error("Request failed with status code 500") });
    show({ lz });
    expect(lz.refresh).toHaveBeenCalledTimes(1); // on opening

    expect(screen.getByText(/Your saved LZ\/PZs could not be loaded/)).toBeInTheDocument();
    expect(screen.queryByText(/Nothing saved yet/)).toBeNull();
    expect(screen.queryByText("0 saved LZ/PZs")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(lz.refresh).toHaveBeenCalledTimes(2);
  });

  it("says a list that loaded empty has nothing saved yet", () => {
    show({});
    expect(screen.getByText("Nothing saved yet. Save an LZ/PZ from its card in the LZ/PZ panel.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Try again" })).toBeNull();
  });

  it("keeps showing a list that failed to refresh, marked as possibly out of date", () => {
    const routes = source({
      entries: [{ id: 7, name: "ROUTE ALPHA", kind: "sketch", updated_at: "2026-10-01T12:00:00Z" }],
      error: new Error("Network Error"),
    });
    show({ routes });
    fireEvent.click(screen.getByRole("tab", { name: /Routes/ }));
    expect(screen.getByText("ROUTE ALPHA")).toBeInTheDocument();
    expect(screen.getByText(/could not be refreshed, so it may be out of date/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(routes.refresh).toHaveBeenCalledTimes(2);
  });

  it("shows Loading while a retry is under way", () => {
    show({ points: source({ loading: true, error: new Error("Network Error") }) });
    fireEvent.click(screen.getByRole("tab", { name: /Local points/ }));
    expect(screen.getByText("Loading…")).toBeInTheDocument();
    expect(screen.queryByText(/could not be loaded/)).toBeNull();
  });
});
