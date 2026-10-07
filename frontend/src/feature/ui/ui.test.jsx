import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import React, { useState } from "react";
import SaveDialog, { nextFreeName } from "../saveDialog/SaveDialog";
import Dialog, { ConfirmDialog } from "./Dialog";
import Menu, { MenuItem, useMenu } from "./Menu";
import { dateAndTime, timeAgo, whenShort } from "./time";
import { ToastProvider, useToast } from "./Toast";

describe("Dialog", () => {
  it("is a labelled modal that takes focus, keeps it inside, closes on Esc and gives it back", () => {
    const onClose = jest.fn();
    const Opener = () => {
      const [open, setOpen] = useState(false);
      return (
        <>
          <button type="button" onClick={() => setOpen(true)}>Open</button>
          {open && (
            <Dialog title="Save LZ/PZ" subtitle="Name it." onClose={() => { onClose(); setOpen(false); }}
              footer={<button type="button">Last</button>}>
              <input aria-label="Name" />
            </Dialog>
          )}
        </>
      );
    };
    render(<Opener />);
    const opener = screen.getByText("Open");
    opener.focus();
    fireEvent.click(opener);
    const dialog = screen.getByRole("dialog", { name: "Save LZ/PZ" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(dialog).toHaveAccessibleDescription("Name it.");
    expect(screen.getByLabelText("Close")).toHaveFocus();
    // Tab from the last control goes round to the first.
    screen.getByText("Last").focus();
    fireEvent.keyDown(dialog, { key: "Tab" });
    expect(screen.getByLabelText("Close")).toHaveFocus();
    fireEvent.keyDown(dialog, { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(opener).toHaveFocus();
  });

  it("never puts focus on a destructive answer first", () => {
    render(<ConfirmDialog title="Delete “LZ HAWK”?" text="Gone for good." confirmLabel="Delete" confirmTone="danger" onConfirm={() => {}} onCancel={() => {}} />);
    expect(screen.getByRole("button", { name: "Cancel" })).toHaveFocus();
  });
});

describe("SaveDialog", () => {
  const existing = [{ name: "LZ HAWK (SHOPE)", updatedAt: "2026-08-25T21:34:00" }, { name: "LZ HAWK (SHOPE) 2" }];

  it("saves the name on Enter, and refuses an empty one in words", async () => {
    const onSave = jest.fn().mockResolvedValue();
    render(<SaveDialog title="Save LZ/PZ" initialName="LZ/PZ 1" existing={existing} kindPhrase="an LZ/PZ" onSave={onSave} onCancel={() => {}}
      contents={[{ text: "Boundary and slope analysis" }, { icon: "info", text: "Threats and routes are not included." }]} />);
    const field = screen.getByLabelText("Name");
    expect(field).toHaveFocus();
    expect(screen.getByText("Boundary and slope analysis")).toBeInTheDocument();
    fireEvent.change(field, { target: { value: "   " } });
    fireEvent.submit(field);
    expect(await screen.findByText("Give it a name.")).toBeInTheDocument();
    expect(onSave).not.toHaveBeenCalled();
    fireEvent.change(field, { target: { value: " LZ EAGLE " } });
    fireEvent.submit(field);
    await waitFor(() => expect(onSave).toHaveBeenCalledWith("LZ EAGLE", { replace: null }));
  });

  it("offers to keep both or replace when the name is taken", async () => {
    const onSave = jest.fn().mockResolvedValue();
    render(<SaveDialog title="Save LZ/PZ" initialName="lz hawk (shope)" existing={existing} kindPhrase="an LZ/PZ" onSave={onSave} onCancel={() => {}} />);
    expect(screen.getByText("Name is already used.")).toBeInTheDocument();
    expect(screen.getByText(/You already have an LZ\/PZ with this name, last saved Aug 25 at/)).toBeInTheDocument();
    expect(screen.getByText("Save this one as “lz hawk (shope) 3”.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Save as new/ })).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText(/Replace the saved one/));
    fireEvent.click(screen.getByRole("button", { name: /Replace/ }));
    await waitFor(() => expect(onSave).toHaveBeenCalledWith("lz hawk (shope)", { replace: existing[0] }));
  });

  it("says when it could not save and lets the person try again", async () => {
    const onSave = jest.fn().mockRejectedValueOnce(new Error("Network Error"));
    render(<SaveDialog title="Save routes" initialName="RED" onSave={onSave} onCancel={() => {}} />);
    fireEvent.click(screen.getByRole("button", { name: /^Save$/ }));
    expect(await screen.findByText(/could not be saved/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^Save$/ })).toBeEnabled();
  });

  it("numbers a copy after the names already taken", () => {
    expect(nextFreeName("LZ A", [{ name: "lz a" }, { name: "LZ A 2" }])).toBe("LZ A 3");
    expect(nextFreeName("LZ B", [{ name: "lz a" }])).toBe("LZ B");
  });
});

describe("Menu", () => {
  const Harness = ({ onPick }) => {
    const menu = useMenu();
    return (
      <>
        <button type="button" {...menu.buttonProps}>Import</button>
        <Menu open={menu.open} onClose={menu.close} anchorRef={menu.anchorRef} label="Import">
          <MenuItem title="Mission file" onSelect={() => onPick("msnx")} onClose={menu.close} />
          <MenuItem title="Local points" onSelect={() => onPick("lps")} onClose={menu.close} />
        </Menu>
      </>
    );
  };

  it("opens from its button, moves with the arrow keys, picks, and closes on Esc", () => {
    const onPick = jest.fn();
    render(<Harness onPick={onPick} />);
    const button = screen.getByText("Import");
    fireEvent.click(button);
    expect(button).toHaveAttribute("aria-expanded", "true");
    const menu = screen.getByRole("menu", { name: "Import" });
    expect(screen.getByRole("menuitem", { name: "Mission file" })).toHaveFocus();
    fireEvent.keyDown(menu, { key: "ArrowDown" });
    expect(screen.getByRole("menuitem", { name: "Local points" })).toHaveFocus();
    fireEvent.click(screen.getByRole("menuitem", { name: "Local points" }));
    expect(onPick).toHaveBeenCalledWith("lps");
    expect(screen.queryByRole("menu")).toBeNull();
    fireEvent.click(button);
    fireEvent.keyDown(screen.getByRole("menu"), { key: "Escape" });
    expect(screen.queryByRole("menu")).toBeNull();
    expect(button).toHaveFocus();
  });
});

describe("Toasts", () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => jest.useRealTimers());

  it("announces a note with its action, and takes it away after a while", () => {
    const action = jest.fn();
    const Show = () => {
      const { show } = useToast();
      return <button type="button" onClick={() => show({ message: "Saved “LZ HAWK” to your Library", action: { label: "Open Library", onClick: action } })}>go</button>;
    };
    render(<ToastProvider><Show /></ToastProvider>);
    fireEvent.click(screen.getByText("go"));
    expect(screen.getByRole("status")).toHaveTextContent("Saved “LZ HAWK” to your Library");
    fireEvent.click(screen.getByText("Open Library"));
    expect(action).toHaveBeenCalled();
    expect(screen.queryByText(/Saved “LZ HAWK”/)).toBeNull();
    fireEvent.click(screen.getByText("go"));
    act(() => { jest.advanceTimersByTime(9000); });
    expect(screen.queryByText(/Saved “LZ HAWK”/)).toBeNull();
  });
});

describe("times", () => {
  const now = new Date(2026, 9, 7, 15, 0);
  it("writes them as crews read them", () => {
    expect(whenShort(new Date(2026, 9, 7, 14, 2), now)).toBe("14:02");
    expect(whenShort(new Date(2026, 9, 3, 9, 12), now)).toBe("Oct 3");
    expect(dateAndTime(new Date(2026, 7, 25, 17, 34), now)).toBe("Aug 25 · 17:34");
    expect(timeAgo(new Date(2026, 9, 7, 14, 56), now)).toBe("4 min ago");
    expect(timeAgo(new Date(2026, 9, 7, 13, 0), now)).toBe("2 h ago");
    expect(whenShort(null)).toBe("");
  });
});
