import { fireEvent, render, screen } from "@testing-library/react";
import React from "react";
import PointDialog from "./PointDialog";

const SET = { id: "s1", name: "OBJ PEACH", points: [{ id: "a", name: "A", group: "North", lat: 34, lon: -84 }] };
const type = (label, value) => fireEvent.change(screen.getByLabelText(label), { target: { value } });
const setup = (ask, sets = [SET]) => {
  const onSave = vi.fn();
  render(<PointDialog ask={ask} sets={sets} near={{ lat: 34, lon: -84 }} onCancel={vi.fn()} onSave={onSave} />);
  return onSave;
};

describe("the point form", () => {
  it("makes a point at a typed grid, in the set chosen", () => {
    const onSave = setup({});
    type("Name", "hawk");
    type("Position", "16S GC 31204 58811");
    type("Elevation (ft)", "1730");
    fireEvent.click(screen.getByRole("button", { name: "Add point" }));
    const saved = onSave.mock.calls[0][0];
    expect(saved).toMatchObject({ setId: "s1", pointId: null, point: { name: "HAWK", elevationFt: 1730, group: "Default" } });
    expect(saved.point.lat).toBeCloseTo(33.944, 2);
  });

  it("says what is missing and saves nothing", () => {
    const onSave = setup({});
    fireEvent.click(screen.getByRole("button", { name: "Add point" }));
    expect(screen.getByText("Give the point a name.")).toBeInTheDocument();
    expect(screen.getByText(/Enter an MGRS grid/)).toBeInTheDocument();
    expect(onSave).not.toHaveBeenCalled();
  });

  it("lets go of a complaint once its field has changed, and only that field's", () => {
    setup({});
    fireEvent.click(screen.getByRole("button", { name: "Add point" }));
    type("Name", "hawk");
    expect(screen.queryByText("Give the point a name.")).not.toBeInTheDocument();
    expect(screen.getByText(/Enter an MGRS grid/)).toBeInTheDocument();
  });

  it("offers a new set where there is none to add to", () => {
    const onSave = setup({ at: { lat: 34.5, lon: -84.25 } }, []);
    type("Name", "hawk");
    fireEvent.click(screen.getByRole("button", { name: "Add point" }));
    expect(onSave.mock.calls[0][0]).toMatchObject({ setId: null, newSetName: "CUSTOM POINTS" });
  });

  it("keeps the exact place a right-click gave, while the grid is left alone", () => {
    const onSave = setup({ at: { lat: 34.123456789, lon: -84.987654321 } });
    type("Name", "hawk");
    fireEvent.click(screen.getByRole("button", { name: "Add point" }));
    expect(onSave.mock.calls[0][0].point).toMatchObject({ lat: 34.123456789, lon: -84.987654321 });
  });

  it("edits a point in place, keeping its id, and moves it only if asked", () => {
    const point = { id: "p1", name: "HAWK", description: "", group: "North", icon: "", elevationFt: 0, lat: 34.123456789, lon: -84.987654321 };
    const onSave = setup({ setId: "s1", point });
    expect(screen.getByLabelText("Elevation (ft)").value).toBe("0");
    type("Name", "dove");
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    expect(onSave.mock.calls[0][0]).toMatchObject({ setId: "s1", pointId: "p1", point: { name: "DOVE", lat: 34.123456789, elevationFt: 0 } });
  });

  it("makes the person choose between two readings of a longitude with no sign", () => {
    const onSave = setup({});
    type("Name", "hawk");
    type("Position", "34.5 84.25");
    const west = screen.getAllByRole("radio").find((radio) => /W/.test(radio.textContent));
    fireEvent.click(west);
    fireEvent.click(screen.getByRole("button", { name: "Add point" }));
    expect(onSave.mock.calls[0][0].point.lon).toBeCloseTo(-84.25);
  });
});
