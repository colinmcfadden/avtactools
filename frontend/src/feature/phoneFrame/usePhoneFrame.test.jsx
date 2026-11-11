import { act, fireEvent, render, renderHook, screen } from "@testing-library/react";
import React from "react";
import PhoneBar from "./PhoneBar";
import usePhoneFrame from "./usePhoneFrame";

const frame = (props = {}) => renderHook((now) => usePhoneFrame(now), { initialProps: { phone: true, step: "lz-1:targeted", ...props } });
// How things stood as the finger came down, as MapTap hands it back.
const tap = (hook, stood = {}) => act(() => hook.result.current.onTap({ phone: true, presenting: false, drawing: false, menuOpen: false, searchOpen: false, dismissed: false, ...stood }));

describe("the phone's frame, put away and brought back", () => {
  it("is hidden by a tap on the map and shown by the next, and says so to a screen reader", () => {
    const hook = frame();
    expect(hook.result.current.hidden).toBe(false);
    tap(hook);
    expect(hook.result.current.hidden).toBe(true);
    expect(hook.result.current.status).toMatch(/Tools hidden.*Menu/);
    tap(hook);
    expect(hook.result.current.hidden).toBe(false);
    expect(hook.result.current.status).toBe("Tools shown.");
  });

  it("is not hidden by a tap that was a point, that folded the search, or that put something away", () => {
    const hook = frame();
    tap(hook, { drawing: true });
    tap(hook, { searchOpen: true });
    tap(hook, { dismissed: true });
    tap(hook, { presenting: true });
    expect(hook.result.current.hidden).toBe(false);
  });

  it("is never hidden on a wide window", () => {
    const hook = frame({ phone: false });
    tap(hook, { phone: false });
    expect(hook.result.current.hidden).toBe(false);
  });

  it("comes back for Escape, the way for a keyboard", () => {
    const hook = frame();
    tap(hook);
    expect(hook.result.current.hidden).toBe(true);
    fireEvent.keyDown(window, { key: "Escape" });
    expect(hook.result.current.hidden).toBe(false);
  });

  it("comes back when the LZ/PZ reaches a new step, with its tools unfolded: there is something new to do", () => {
    const hook = frame();
    act(() => hook.result.current.toggleTools());
    tap(hook);
    expect(hook.result.current).toMatchObject({ hidden: true, toolsFolded: true });
    hook.rerender({ phone: true, step: "lz-1:analyzed" });
    expect(hook.result.current).toMatchObject({ hidden: false, toolsFolded: false });
  });

  it("comes back for something being drawn, and stays back after it", () => {
    const hook = frame();
    tap(hook);
    hook.rerender({ phone: true, step: "lz-1:targeted", drawing: true });
    expect(hook.result.current.hidden).toBe(false);
    hook.rerender({ phone: true, step: "lz-1:targeted", drawing: false });
    expect(hook.result.current.hidden).toBe(false);
  });

  it("folds and unfolds the tools from the button under them", () => {
    const hook = frame();
    act(() => hook.result.current.toggleTools());
    expect(hook.result.current.toolsFolded).toBe(true);
    act(() => hook.result.current.toggleTools());
    expect(hook.result.current.toolsFolded).toBe(false);
  });
});

describe("the menu under the phone's search bar", () => {
  it("opens and closes from its button", () => {
    const hook = frame();
    act(() => hook.result.current.toggleMenu());
    expect(hook.result.current.menuOpen).toBe(true);
    act(() => hook.result.current.toggleMenu());
    expect(hook.result.current.menuOpen).toBe(false);
  });

  it("is only closed by a tap on the map while it is open", () => {
    const hook = frame();
    act(() => hook.result.current.toggleMenu());
    tap(hook, { menuOpen: true });
    expect(hook.result.current).toMatchObject({ menuOpen: false, hidden: false });
  });

  it("brings the frame back as it opens: its button is the part of the frame that never goes", () => {
    const hook = frame();
    tap(hook);
    act(() => hook.result.current.toggleMenu());
    expect(hook.result.current).toMatchObject({ hidden: false, menuOpen: true });
  });

  it("closes on Escape, and on a press anywhere but it and what was opened from it", () => {
    const hook = frame();
    act(() => hook.result.current.toggleMenu());
    fireEvent.keyDown(window, { key: "Escape" });
    expect(hook.result.current.menuOpen).toBe(false);

    const inside = document.createElement("div");
    inside.className = "ui-menu";
    document.body.append(inside);
    act(() => hook.result.current.toggleMenu());
    fireEvent.pointerDown(inside);
    expect(hook.result.current.menuOpen).toBe(true);
    fireEvent.pointerDown(document.body);
    expect(hook.result.current.menuOpen).toBe(false);
    inside.remove();
  });

  it("closes when the frame is put away, and is not there at all on a wide window or under a brief", () => {
    const hook = frame();
    act(() => hook.result.current.toggleMenu());
    tap(hook, { menuOpen: false });
    expect(hook.result.current).toMatchObject({ hidden: true, menuOpen: false });

    const wide = frame({ phone: false });
    act(() => wide.result.current.openMenu());
    expect(wide.result.current.menuOpen).toBe(false);
    wide.rerender({ phone: true, step: "lz-1:targeted" });
    expect(wide.result.current.menuOpen).toBe(false);

    const briefed = frame();
    act(() => briefed.result.current.toggleMenu());
    briefed.rerender({ phone: true, step: "lz-1:targeted", presenting: true });
    expect(briefed.result.current.menuOpen).toBe(false);
  });

  it("reads how things stand as the finger comes down, from the page", () => {
    const hook = frame();
    expect(hook.result.current.beforeTap()).toMatchObject({ phone: true, drawing: false, menuOpen: false, searchOpen: false, dismissed: false });
    const popup = document.createElement("div");
    popup.className = "leaflet-popup";
    const search = document.createElement("div");
    search.className = "topbar-spotlight topbar-spotlight--open";
    document.body.append(popup, search);
    expect(hook.result.current.beforeTap()).toMatchObject({ searchOpen: true, dismissed: true });
    popup.remove();
    search.remove();
  });
});

describe("the phone's search bar", () => {
  it("has the search, and a named menu button that says whether the menu is open", () => {
    const onToggleMenu = vi.fn();
    const { rerender } = render(<PhoneBar search={<input aria-label="Search" />} onToggleMenu={onToggleMenu} />);
    const menu = screen.getByRole("button", { name: "Menu" });
    expect(menu).toHaveAttribute("aria-expanded", "false");
    expect(menu).toHaveAttribute("aria-controls", "phone-menu");
    fireEvent.click(menu);
    expect(onToggleMenu).toHaveBeenCalledTimes(1);
    rerender(<PhoneBar search={<input aria-label="Search" />} menuOpen onToggleMenu={onToggleMenu} />);
    expect(screen.getByRole("button", { name: "Menu" })).toHaveAttribute("aria-expanded", "true");
  });

  it("puts the menu away as the search is taken up", () => {
    const onSearchStart = vi.fn();
    render(<PhoneBar search={<input aria-label="Search" />} onSearchStart={onSearchStart} />);
    fireEvent.focus(screen.getByRole("textbox", { name: "Search" }));
    expect(onSearchStart).toHaveBeenCalled();
  });

  it("says how a Mission Pack stands on the menu button, where the bar's words are put away", () => {
    render(<PhoneBar search={null} pack={{ name: "OP DK" }} packStatus="waiting" />);
    expect(screen.getByRole("button", { name: "Menu. OP DK: Offline · changes will send when back" })).toBeInTheDocument();
    expect(document.querySelector(".phone-bar__dot--queued")).not.toBeNull();
  });
});
