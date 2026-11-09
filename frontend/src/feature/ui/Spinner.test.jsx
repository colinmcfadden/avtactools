import { fireEvent, render, screen } from "@testing-library/react";
import React from "react";
import { BusyButton, ProgressBar, Spinner } from "./Spinner";

describe("a button that waits on the server", () => {
  it("shows its icon and can be pressed while idle", () => {
    const onClick = vi.fn();
    const { container } = render(<BusyButton className="ui-btn ui-btn--primary" icon="download" onClick={onClick}>Download PDF</BusyButton>);
    const button = screen.getByRole("button", { name: "Download PDF" });
    expect(button).toBeEnabled();
    expect(button).not.toHaveAttribute("aria-busy");
    expect(container.querySelector(".ui-spinner")).toBeNull();
    expect(container.querySelector("svg")).not.toBeNull();
    fireEvent.click(button);
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it("turns a spinner where its icon was, says it is busy and cannot be pressed again", () => {
    const onClick = vi.fn();
    const { container } = render(<BusyButton className="ui-btn ui-btn--primary" icon="download" busy onClick={onClick}>Printing…</BusyButton>);
    const button = screen.getByRole("button", { name: "Printing…" });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute("aria-busy", "true");
    expect(button).toHaveClass("ui-btn--primary", "ui-btn--busy");
    expect(button.querySelector(".ui-spinner")).toHaveAttribute("aria-hidden", "true");
    expect(container.querySelector("svg")).toBeNull();
    fireEvent.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });

  it("puts a spinner before its words when it has no icon, and stays off for its own reasons when idle", () => {
    const { rerender } = render(<BusyButton busy>Make the book</BusyButton>);
    expect(screen.getByRole("button").firstChild).toHaveClass("ui-spinner");
    rerender(<BusyButton disabled>Make the book</BusyButton>);
    expect(screen.getByRole("button")).toBeDisabled();
    expect(screen.getByRole("button")).not.toHaveClass("ui-btn--busy");
  });
});

describe("a spinner standing alone", () => {
  it("is announced when it is given words, and hidden when it is decoration", () => {
    const { rerender } = render(<Spinner label="Loading the books" />);
    expect(screen.getByRole("status", { name: "Loading the books" })).toBeInTheDocument();
    rerender(<Spinner />);
    expect(screen.queryByRole("status")).toBeNull();
  });
});

describe("progress the server counts", () => {
  it("fills to how far it has got, and says so in words", () => {
    render(<ProgressBar done={3} total={12} label="Printing the book" />);
    const bar = screen.getByRole("progressbar", { name: "Printing the book" });
    expect(bar).toHaveAttribute("aria-valuenow", "3");
    expect(bar).toHaveAttribute("aria-valuemax", "12");
    expect(bar).toHaveAttribute("aria-valuetext", "3 of 12");
    expect(bar.firstChild).toHaveStyle({ width: "25%" });
  });

  it("sweeps while nothing is counted yet", () => {
    render(<ProgressBar done={0} total={12} label="Printing the book" />);
    const bar = screen.getByRole("progressbar", { name: "Printing the book" });
    expect(bar).toHaveClass("ui-progress--sweep");
    expect(bar).not.toHaveAttribute("aria-valuenow");
  });
});
