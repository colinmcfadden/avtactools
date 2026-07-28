import React from "react";
import { render, screen, fireEvent } from "@testing-library/react";
import AircraftPicker from "./AircraftPicker";
import { FALLBACK_PROFILE, normalizeProfile } from "./aircraftProfiles";

/**
 * The picker sits inside the control panel that hosts the whole map workspace,
 * so it must never throw. It shipped once with its props unwired in App.js and
 * took the app down with "Cannot read properties of undefined".
 */

const CH47 = normalizeProfile({
  slug: "ch47f", designation: "CH-47F", name: "CH-47F Chinook",
  rotor_diameter_m: 30.14, default_airspeed_kts: 130, is_system: true,
});

describe("AircraftPicker resilience", () => {
  it("renders with no props at all", () => {
    expect(() => render(<AircraftPicker />)).not.toThrow();
    // Falls back to the built-in aircraft rather than a blank control.
    expect(screen.getByRole("combobox")).toHaveValue(FALLBACK_PROFILE.slug);
  });

  it("renders while the profile list is still loading", () => {
    expect(() =>
      render(<AircraftPicker profiles={[]} activeProfile={undefined} />),
    ).not.toThrow();
    expect(screen.getByRole("combobox")).toHaveValue(FALLBACK_PROFILE.slug);
  });

  it("survives a list containing holes", () => {
    expect(() =>
      render(<AircraftPicker profiles={[null, CH47, undefined]} activeProfile={CH47} />),
    ).not.toThrow();
    expect(screen.getAllByRole("option")).toHaveLength(1);
  });

  it("does not throw when clicked without an onSelect handler", () => {
    render(<AircraftPicker profiles={[CH47]} activeProfile={CH47} />);
    expect(() =>
      fireEvent.change(screen.getByRole("combobox"), { target: { value: "ch47f" } }),
    ).not.toThrow();
  });

  it("renders in compact mode without props", () => {
    expect(() => render(<AircraftPicker compact />)).not.toThrow();
  });
});

describe("AircraftPicker behaviour", () => {
  it("shows the active aircraft's derived spacing and cruise speed", () => {
    render(<AircraftPicker profiles={[CH47]} activeProfile={CH47} />);
    // 30.14 m rotor + 60 m clearance
    expect(screen.getByText(/90 m spacing/)).toBeInTheDocument();
    expect(screen.getByText(/130 kt/)).toBeInTheDocument();
  });

  it("reports the chosen slug", () => {
    const onSelect = jest.fn();
    render(
      <AircraftPicker
        profiles={[normalizeProfile(FALLBACK_PROFILE), CH47]}
        activeProfile={normalizeProfile(FALLBACK_PROFILE)}
        onSelect={onSelect}
      />,
    );
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "ch47f" } });
    expect(onSelect).toHaveBeenCalledWith("ch47f");
  });

  it("flags unverified performance figures", () => {
    const seeded = normalizeProfile({ ...CH47, perf_source: "published" });
    render(<AircraftPicker profiles={[seeded]} activeProfile={seeded} />);
    expect(screen.getByText("unverified perf")).toBeInTheDocument();
  });

  it("hides the manage action without the entitlement", () => {
    const { rerender } = render(
      <AircraftPicker profiles={[CH47]} activeProfile={CH47} canManage={false} />,
    );
    expect(screen.queryByRole("button", { name: /manage/i })).toBeNull();
    rerender(<AircraftPicker profiles={[CH47]} activeProfile={CH47} canManage />);
    expect(screen.getByRole("button", { name: /manage/i })).toBeInTheDocument();
  });
});
