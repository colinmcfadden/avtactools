import { act, fireEvent, render, screen } from "@testing-library/react";
import React from "react";
import { ToastProvider } from "../feature/ui/Toast";
import CopyGridTitle from "./CopyGridTitle";

const GRID = "16S GC 28864 55349";

const renderTitle = (onCopied = vi.fn()) => {
  render(
    <ToastProvider>
      <CopyGridTitle grid={GRID} onCopied={onCopied} />
    </ToastProvider>,
  );
  return onCopied;
};

describe("the grid at the top of the map's menu", () => {
  const original = navigator.clipboard;
  afterEach(() => {
    Object.defineProperty(navigator, "clipboard", { value: original, configurable: true });
  });

  it("is a button a keyboard and a screen reader reach, named for what it does", () => {
    renderTitle();
    const button = screen.getByRole("button", { name: `Copy grid ${GRID}` });
    expect(button).toHaveTextContent(GRID);
    expect(button).toHaveAttribute("type", "button");
    button.focus();
    expect(button).toHaveFocus();
  });

  it("copies the grid when clicked, says so and closes the menu", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    const onCopied = renderTitle();
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: `Copy grid ${GRID}` }));
    });
    expect(writeText).toHaveBeenCalledWith(GRID);
    expect(screen.getByText("Copied")).toBeInTheDocument();
    expect(onCopied).toHaveBeenCalledTimes(1);
  });

  it("copies the older way where the clipboard API is refused", async () => {
    Object.defineProperty(navigator, "clipboard", { value: { writeText: vi.fn().mockRejectedValue(new Error("denied")) }, configurable: true });
    const copied = [];
    document.execCommand = vi.fn(() => {
      copied.push(document.querySelector("textarea")?.value);
      return true;
    });
    renderTitle();
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: `Copy grid ${GRID}` }));
    });
    expect(copied).toEqual([GRID]);
    expect(screen.getByText("Copied")).toBeInTheDocument();
    expect(document.querySelector("textarea")).toBeNull();
    delete document.execCommand;
  });

  it("says so when the browser will not copy", async () => {
    const writeText = vi.fn().mockRejectedValue(new Error("denied"));
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    document.execCommand = vi.fn(() => false);
    renderTitle();
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: `Copy grid ${GRID}` }));
    });
    expect(screen.getByText("The grid could not be copied.")).toBeInTheDocument();
    expect(screen.queryByText("Copied")).toBeNull();
    delete document.execCommand;
  });
});
