import React, { useState } from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import TopBarSearch, { OPEN_WIDTH, openBox } from "./TopBarSearch";

const setup = () => {
  const onChoose = vi.fn();
  const onShare = vi.fn();
  const Harness = () => {
    const [value, setValue] = useState("");
    return (
      <>
        <TopBarSearch value={value} onChange={setValue} onChoose={onChoose} onShare={onShare} />
        <button type="button">elsewhere</button>
      </>
    );
  };
  render(<Harness />);
  const input = screen.getByRole("combobox");
  const isOpen = () => document.querySelector(".topbar-spotlight").classList.contains("topbar-spotlight--open");
  return { input, isOpen, onChoose, onShare };
};

describe("TopBarSearch", () => {
  it("starts empty and folded, so it shows what it takes", () => {
    const { input, isOpen } = setup();
    expect(input).toHaveValue("");
    expect(input).toHaveAttribute("placeholder", "Search by grid or lat/long");
    expect(isOpen()).toBe(false);
  });

  it("opens when the field takes the focus and folds when the focus leaves it", () => {
    const { input, isOpen } = setup();
    fireEvent.focus(input);
    expect(isOpen()).toBe(true);
    expect(document.querySelector(".topbar-spotlight-scrim--shown")).not.toBeNull();
    fireEvent.blur(input, { relatedTarget: screen.getByRole("button", { name: "elsewhere" }) });
    expect(isOpen()).toBe(false);
  });

  it("stays open while the focus moves to its own buttons", () => {
    const { input, isOpen } = setup();
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: "16S GC 28864 55349" } });
    fireEvent.blur(input, { relatedTarget: screen.getByRole("button", { name: "GO" }) });
    expect(isOpen()).toBe(true);
  });

  it("folds on the Escape after the one that closes the list, keeping the focus", () => {
    const { input, isOpen } = setup();
    input.focus();
    fireEvent.change(input, { target: { value: "34 84" } });
    fireEvent.keyDown(input, { key: "Escape" }); // the list
    expect(isOpen()).toBe(true);
    fireEvent.keyDown(input, { key: "Escape" });
    expect(isOpen()).toBe(false);
    expect(document.activeElement).toBe(input);
    fireEvent.change(input, { target: { value: "34 8" } }); // typing again opens it
    expect(isOpen()).toBe(true);
  });

  it("folds once a place is chosen, and a click opens it again", () => {
    const { input, isOpen, onChoose } = setup();
    input.focus();
    fireEvent.change(input, { target: { value: "16S GC 28864 55349" } });
    fireEvent.keyDown(input, { key: "Enter" });
    expect(onChoose).toHaveBeenCalledWith(expect.objectContaining({ mgrs: "16S GC 28864 55349" }));
    expect(isOpen()).toBe(false);
    fireEvent.pointerDown(input);
    expect(isOpen()).toBe(true);
  });

  it("folds once a place is shared", () => {
    const { input, isOpen, onShare } = setup();
    input.focus();
    fireEvent.change(input, { target: { value: "16S GC 28864 55349" } });
    fireEvent.click(screen.getByRole("button", { name: /^Share grid/ }));
    expect(onShare).toHaveBeenCalled();
    expect(isOpen()).toBe(false);
  });
});

describe("TopBarSearch under a pointer", () => {
  it("keeps the focus in the field while GO is pressed, so a field folding on blur is still there for the click", () => {
    const { input, isOpen, onChoose } = setup();
    input.focus();
    fireEvent.change(input, { target: { value: "16S GC 28864 55349" } });
    const go = screen.getByRole("button", { name: "GO" });
    expect(fireEvent.mouseDown(go)).toBe(false); // the focus stays where it is
    expect(isOpen()).toBe(true);
    fireEvent.click(go, { detail: 1 });
    expect(onChoose).toHaveBeenCalledWith(expect.objectContaining({ mgrs: "16S GC 28864 55349" }));
    expect(isOpen()).toBe(false);
    expect(document.activeElement).not.toBe(input); // a click puts the field away, as choosing a row does
  });

  it("opens on a press only by the focus it gives, so a touch dragged away or cancelled opens nothing", () => {
    const { input, isOpen } = setup();
    fireEvent.pointerDown(input);
    expect(isOpen()).toBe(false);
  });

  it("a press on its magnifier or padding goes to the text instead of folding it", () => {
    const { input, isOpen } = setup();
    input.focus();
    fireEvent.focus(input); // jsdom's focus() reaches no React focus handler
    expect(isOpen()).toBe(true);
    const magnifier = document.querySelector(".topbar-search__lead");
    expect(fireEvent.mouseDown(magnifier)).toBe(false);
    expect(document.activeElement).toBe(input);
    expect(isOpen()).toBe(true);
    // Folded, the same press opens it.
    fireEvent.blur(input, { relatedTarget: screen.getByRole("button", { name: "elsewhere" }) });
    screen.getByRole("button", { name: "elsewhere" }).focus();
    expect(isOpen()).toBe(false);
    fireEvent.mouseDown(document.querySelector(".topbar-search"));
    expect(document.activeElement).toBe(input); // which opens it, by its focus (the browser's focus event; jsdom sends none)
  });
});

describe("openBox", () => {
  it("grows to OPEN_WIDTH centred on the slot", () => {
    expect(openBox({ left: 500, width: 400 }, 1600)).toEqual({ left: -(OPEN_WIDTH - 400) / 2, width: OPEN_WIDTH });
  });
  it("keeps 16 px from the window's edges", () => {
    expect(openBox({ left: 1000, width: 200 }, 1100)).toEqual({ left: 1100 - 16 - OPEN_WIDTH - 1000, width: OPEN_WIDTH });
    expect(openBox({ left: 20, width: 200 }, 1600)).toEqual({ left: -4, width: OPEN_WIDTH });
  });
  it("is never wider than the window allows, nor narrower than the slot", () => {
    expect(openBox({ left: 200, width: 300 }, 600)).toEqual({ left: -184, width: 568 });
    expect(openBox({ left: 0, width: 900 }, 920)).toEqual({ left: 0, width: 900 });
  });
});
