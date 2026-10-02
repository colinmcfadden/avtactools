import { fireEvent, render, screen, within } from "@testing-library/react";
import ActiveLzWindow from "./ActiveLzWindow";

const DIAGRAMS = [
  { id: "a", name: "LZ/PZ 1", status: "targeted", target: { mgrs: "16S GC 28864 55349" } },
  { id: "b", name: "LZ/PZ 2", status: "analyzed", target: { mgrs: "16S GD 54928 39444" } },
  { id: "c", name: "LZ/PZ 3", status: "draft" },   // no target yet
];

const renderWindow = (props = {}) => render(
  <ActiveLzWindow diagrams={DIAGRAMS} activeDiagramId="a" onSelect={jest.fn()}
                  onSave={jest.fn()} onRemove={jest.fn()} onView3D={jest.fn()} {...props} />,
);

it("puts a 3D button on every row", () => {
  renderWindow();
  for (const name of ["LZ/PZ 1", "LZ/PZ 2", "LZ/PZ 3"]) {
    expect(screen.getByRole("button", { name: `Open ${name} in 3D` })).toBeInTheDocument();
  }
});

it("opens that row's LZ, not the active one", () => {
  const onView3D = jest.fn();
  renderWindow({ onView3D });
  fireEvent.click(screen.getByRole("button", { name: "Open LZ/PZ 2 in 3D" }));
  expect(onView3D).toHaveBeenCalledWith("b", DIAGRAMS[1]);
});

it("does not also select the row when its 3D button is pressed", () => {
  // The 3D button is a sibling of the row; a click on it must not bubble into
  // a second, separate selection.
  const onSelect = jest.fn();
  renderWindow({ onSelect });
  fireEvent.click(screen.getByRole("button", { name: "Open LZ/PZ 2 in 3D" }));
  expect(onSelect).not.toHaveBeenCalled();
});

it("disables 3D for an LZ without a target", () => {
  renderWindow();
  expect(screen.getByRole("button", { name: "Open LZ/PZ 3 in 3D" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Open LZ/PZ 1 in 3D" })).toBeEnabled();
});

it("no longer has a 3D button in the bottom bar", () => {
  const { container } = renderWindow();
  const actions = container.querySelector(".active-lz-window__actions");
  expect(within(actions).queryByText("3D")).toBeNull();
  expect(within(actions).getByText("SAVE")).toBeInTheDocument();
  expect(within(actions).getByText("REMOVE")).toBeInTheDocument();
});

it("never nests a button inside a button", () => {
  // Invalid HTML: browsers re-parent the inner one and the click lands on the
  // wrong element.
  const { container } = renderWindow();
  expect(container.querySelector("button button")).toBeNull();
});

it("leaves the rows alone when no 3D handler is given", () => {
  renderWindow({ onView3D: undefined });
  expect(screen.queryByRole("button", { name: /in 3D$/ })).toBeNull();
});
