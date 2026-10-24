import React, { useState } from "react";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { SandtableBar, SandtableStage, SandtableSteps, useSandtable } from "./Sandtable";
import BriefMenu from "./BriefMenu";
import BriefSetup from "./BriefSetup";
import { useBrief } from "./useBrief";
import { scriptText } from "./compile";
import * as briefApi from "./briefApi";

jest.mock("./briefApi", () => ({
  listBriefs: jest.fn(),
  getBrief: jest.fn(),
  createBrief: jest.fn(),
  updateBrief: jest.fn(),
  weatherAt: jest.fn(),
  printBrief: jest.fn(),
}));

const fixture = require("../../../../contracts/fixtures/brief/compile.json");
const MODEL = fixture.cases[0].expected;
const stepOf = (id) => MODEL.steps.find((s) => s.id === id);

// The sandtable as App holds it: the model, whether it runs, and its three pieces.
let latest = null;
const Harness = ({ model = MODEL, loading = false }) => {
  const [on, setOn] = useState(false);
  const sandtable = useSandtable({ model, on, setOn, loading });
  latest = sandtable;
  return (
    <>
      <button type="button" onClick={sandtable.start}>Present</button>
      {on && (
        <>
          <SandtableBar sandtable={sandtable} place="OP DK" />
          <SandtableSteps sandtable={sandtable} />
          <SandtableStage sandtable={sandtable} />
        </>
      )}
    </>
  );
};

const begin = (props) => {
  const view = render(<Harness {...props} />);
  screen.getByRole("button", { name: "Present" }).focus();
  fireEvent.click(screen.getByRole("button", { name: "Present" }));
  return view;
};
const card = () => screen.getByRole("region", { name: "Step" });
const cardTitle = () => within(card()).getByRole("heading", { level: 2 }).textContent;
const position = () => document.querySelector(".sandtable-bar__position").textContent;
const key = (k, extra = {}) => fireEvent.keyDown(document.body, { key: k, ...extra });

beforeEach(() => {
  jest.useFakeTimers();
  latest = null;
});
afterEach(() => jest.useRealTimers());

describe("the live sandtable", () => {
  it("begins at the first step, with what is briefed, who briefs it and the script", () => {
    begin();
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("OP DK · Air movement brief");
    expect(screen.getByText("Presenting")).toBeInTheDocument();
    expect(position()).toBe(`Step 1 of ${MODEL.steps.length}`);
    expect(cardTitle()).toBe("Roll call");
    expect(within(card()).getByText("AMC")).toBeInTheDocument();
    expect(within(card()).getByText(/^1 Roll call$/)).toBeInTheDocument();
    expect(card()).toHaveTextContent(scriptText(stepOf("1"))[0].slice(0, 40));
    expect(latest.presentation.view.stepId).toBe("1");
  });

  it("never calls a step by a PDF page", () => {
    begin();
    for (let i = 0; i < MODEL.steps.length; i += 1) {
      expect(document.body.textContent).not.toMatch(/\bpages?\s*\d|\bp\d+\b|PDF/i);
      key("ArrowRight");
    }
  });

  it("steps with the arrows, Page Up and Page Down, Home and End, and the card's buttons", () => {
    begin();
    key("ArrowRight");
    expect(position()).toBe(`Step 2 of ${MODEL.steps.length}`);
    key("PageDown");
    expect(position()).toBe(`Step 3 of ${MODEL.steps.length}`);
    key("ArrowLeft");
    key("PageUp");
    expect(position()).toBe(`Step 1 of ${MODEL.steps.length}`);
    expect(screen.getByRole("button", { name: "Previous step" })).toBeDisabled();
    key("ArrowLeft");
    expect(position()).toBe(`Step 1 of ${MODEL.steps.length}`);
    key("End");
    expect(position()).toBe(`Step ${MODEL.steps.length} of ${MODEL.steps.length}`);
    expect(screen.getByRole("button", { name: "Next step" })).toBeDisabled();
    key("Home");
    fireEvent.click(screen.getByRole("button", { name: "Next step" }));
    expect(position()).toBe(`Step 2 of ${MODEL.steps.length}`);
    fireEvent.click(screen.getByRole("button", { name: "Previous step" }));
    expect(position()).toBe(`Step 1 of ${MODEL.steps.length}`);
  });

  it("goes to any step from the list, which says where the brief is", () => {
    begin();
    const steps = screen.getByRole("navigation", { name: "Brief steps" });
    const hawk = stepOf("4.e@lz:lz-hawk");
    fireEvent.click(within(steps).getByRole("button", { name: new RegExp(hawk.title) }));
    expect(cardTitle()).toBe("LZ HAWK");
    expect(within(steps).getByRole("button", { name: new RegExp(hawk.title) })).toHaveAttribute("aria-current", "step");
    expect(within(card()).getByText(/^4\.e Landing sequence$/)).toBeInTheDocument();
    expect(within(card()).getByText("Flight lead")).toBeInTheDocument();
    expect(latest.presentation.view).toMatchObject({ stepId: hawk.id, kind: "stop", focus: "lz:lz-hawk" });
  });

  it("shows a step's facts, a blank as a line to write on, and the mission constants", () => {
    begin();
    act(() => latest.go(MODEL.steps.findIndex((s) => s.id === "4.e@lz:lz-hawk")));
    expect(within(card()).getByText("203°")).toBeInTheDocument();
    expect(card().querySelectorAll(".brief-writein").length).toBeGreaterThan(0);
    const constants = screen.getByRole("region", { name: "Mission constants" });
    MODEL.constants.filter((c) => c.value).forEach((c) => expect(within(constants).getAllByText(c.value).length).toBeGreaterThan(0));
    fireEvent.click(within(constants).getByRole("button", { name: /Mission constants/ }));
    expect(within(constants).queryByText(MODEL.constants.find((c) => c.value).value)).toBeNull();
  });

  it("starts with the steps and constants folded on a narrow window, and folds the steps again once one is chosen", () => {
    const original = window.matchMedia;
    window.matchMedia = (query) => ({ matches: query.includes("max-width"), media: query, addEventListener() {}, removeEventListener() {} });
    try {
      begin();
      expect(screen.queryByRole("navigation", { name: "Brief steps" })).toBeNull();
      expect(screen.getByRole("button", { name: /Mission constants/ })).toHaveAttribute("aria-expanded", "false");
      fireEvent.click(screen.getByRole("button", { name: "Steps" }));
      fireEvent.click(within(screen.getByRole("navigation", { name: "Brief steps" })).getByRole("button", { name: /Situation/ }));
      expect(position()).toBe(`Step 2 of ${MODEL.steps.length}`);
      expect(screen.queryByRole("navigation", { name: "Brief steps" })).toBeNull();
    } finally {
      window.matchMedia = original;
    }
  });

  it("hides and shows the steps", () => {
    begin();
    fireEvent.click(screen.getByRole("button", { name: "Hide steps" }));
    expect(screen.queryByRole("navigation", { name: "Brief steps" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Steps" }));
    expect(screen.getByRole("navigation", { name: "Brief steps" })).toBeInTheDocument();
  });

  it("keeps every key from the rest of the app while it runs, and the browser's save page too", () => {
    const heard = jest.fn();
    window.addEventListener("keydown", heard);
    document.addEventListener("keydown", heard);
    try {
      begin();
      key("ArrowRight");
      key("Delete");
      const save = new KeyboardEvent("keydown", { key: "s", ctrlKey: true, bubbles: true, cancelable: true });
      act(() => {
        document.body.dispatchEvent(save);
      });
      expect(save.defaultPrevented).toBe(true);
      expect(heard).not.toHaveBeenCalled();
    } finally {
      window.removeEventListener("keydown", heard);
      document.removeEventListener("keydown", heard);
    }
  });

  it("leaves a field's keys alone", () => {
    const heard = jest.fn();
    window.addEventListener("keydown", heard);
    try {
      begin();
      const field = document.createElement("input");
      document.body.appendChild(field);
      fireEvent.keyDown(field, { key: "ArrowRight" });
      expect(position()).toBe(`Step 1 of ${MODEL.steps.length}`);
      expect(heard).toHaveBeenCalledTimes(1);
      field.remove();
    } finally {
      window.removeEventListener("keydown", heard);
    }
  });

  it("lets the space bar press a button rather than step past it", () => {
    begin();
    fireEvent.keyDown(screen.getByRole("button", { name: "Next step" }), { key: " " });
    expect(position()).toBe(`Step 1 of ${MODEL.steps.length}`);
    key(" ");
    expect(position()).toBe(`Step 2 of ${MODEL.steps.length}`);
  });

  it("ends with End brief or Escape, gives the keys back, and returns the focus", () => {
    begin();
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Next step" }));
    fireEvent.click(screen.getByRole("button", { name: /End brief/ }));
    act(() => jest.runOnlyPendingTimers());
    expect(screen.queryByRole("region", { name: "Step" })).toBeNull();
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Present" }));

    const heard = jest.fn();
    window.addEventListener("keydown", heard);
    key("ArrowRight");
    expect(heard).toHaveBeenCalledTimes(1);
    window.removeEventListener("keydown", heard);

    fireEvent.click(screen.getByRole("button", { name: "Present" }));
    expect(position()).toBe(`Step 1 of ${MODEL.steps.length}`);
    key("Escape");
    expect(screen.queryByRole("region", { name: "Step" })).toBeNull();
  });

  it("follows a step by what it is when the brief changes under it", () => {
    const { rerender } = begin();
    act(() => latest.go(MODEL.steps.findIndex((s) => s.id === "4.a")));
    const without = { ...MODEL, steps: MODEL.steps.filter((s) => s.id !== "2") };
    rerender(<Harness model={without} />);
    expect(cardTitle()).toBe(stepOf("4.a").title);
    expect(position()).toBe(`Step ${without.steps.findIndex((s) => s.id === "4.a") + 1} of ${without.steps.length}`);
    // The step itself went: the presenter stays where it was in the list.
    const at = without.steps.findIndex((s) => s.id === "4.a");
    const gone = { ...without, steps: without.steps.filter((s) => s.id !== "4.a") };
    rerender(<Harness model={gone} />);
    expect(cardTitle()).toBe(gone.steps[at].title.split(" · ").pop());
  });

  it("keeps following the step it shows once the one it followed has gone, never another that lands at its place", () => {
    const { rerender } = begin();
    const hawk = MODEL.steps.findIndex((s) => s.id === "4.e@lz:lz-hawk");
    act(() => latest.go(hawk));
    const withoutHawk = { ...MODEL, steps: MODEL.steps.filter((s) => s.id !== "4.e@lz:lz-hawk") };
    rerender(<Harness model={withoutHawk} />);
    const shown = withoutHawk.steps[hawk];
    expect(latest.presentation.view.stepId).toBe(shown.id);
    // Someone adds a step where HAWK was, and puts HAWK back: the presenter stays on the step they are briefing.
    const falcon = { ...stepOf("4.e@lz:lz-hawk"), id: "4.e@lz:lz-falcon", title: "Landing sequence · LZ FALCON" };
    rerender(<Harness model={{ ...withoutHawk, steps: [...withoutHawk.steps.slice(0, hawk), falcon, ...withoutHawk.steps.slice(hawk)] }} />);
    expect(latest.presentation.view.stepId).toBe(shown.id);
    rerender(<Harness model={MODEL} />);
    expect(latest.presentation.view.stepId).toBe(shown.id);
  });

  it("shows and flies to nothing while the brief is still being read, then begins at its first step", () => {
    const { rerender } = begin({ loading: true });
    expect(card()).toHaveTextContent("Opening the brief…");
    expect(latest.presentation.view).toBeNull();
    key("ArrowRight");
    rerender(<Harness loading={false} />);
    expect(position()).toBe(`Step 1 of ${MODEL.steps.length}`);
    expect(latest.presentation.view.stepId).toBe("1");
  });

  it("gives the focus to the Brief button at the end when what it began from has gone", () => {
    const briefButton = document.createElement("button");
    briefButton.className = "brief-menu__button";
    document.body.appendChild(briefButton);
    try {
      render(<Harness />);
      const item = document.createElement("button");
      document.body.appendChild(item);
      item.focus();
      act(() => latest.start());
      item.remove();
      fireEvent.click(screen.getByRole("button", { name: /End brief/ }));
      act(() => jest.runOnlyPendingTimers());
      expect(document.activeElement).toBe(briefButton);
    } finally {
      briefButton.remove();
    }
  });

  it("keeps the focus where it is when a step control is pressed with the mouse, so the space bar goes on stepping", () => {
    begin();
    key("ArrowRight");
    const previous = screen.getByRole("button", { name: "Previous step" });
    expect(fireEvent.mouseDown(previous)).toBe(false);
    const steps = screen.getByRole("navigation", { name: "Brief steps" });
    expect(fireEvent.mouseDown(within(steps).getAllByRole("button")[2])).toBe(false);
    expect(fireEvent.mouseDown(screen.getByRole("button", { name: /Mission constants/ }))).toBe(false);
  });

  it("says each step to a screen reader as it comes", () => {
    begin();
    key("ArrowRight");
    const live = card().querySelector('[aria-live="polite"]');
    expect(live).toHaveTextContent(`Step 2 of ${MODEL.steps.length}: ${MODEL.steps[1].title}`);
  });
});

describe("the ways into the sandtable", () => {
  it("is offered in the Brief menu", () => {
    const onPresent = jest.fn();
    render(<BriefMenu packName="OP DK" summary="5 LZ/PZs" onGenerate={() => {}} onPresent={onPresent} />);
    fireEvent.click(screen.getByRole("button", { name: /Brief/ }));
    fireEvent.click(screen.getByRole("menuitem", { name: /Present live sandtable/ }));
    expect(onPresent).toHaveBeenCalledTimes(1);
  });

  it("is not offered where nothing can present it", () => {
    render(<BriefMenu packName="OP DK" summary="5 LZ/PZs" onGenerate={() => {}} />);
    fireEvent.click(screen.getByRole("button", { name: /Brief/ }));
    expect(screen.queryByRole("menuitem", { name: /Present live sandtable/ })).toBeNull();
  });

  it("is offered beside Download PDF at the review, keeping the answers first", async () => {
    briefApi.weatherAt.mockResolvedValue(null);
    briefApi.createBrief.mockResolvedValue({ id: 3 });
    briefApi.updateBrief.mockResolvedValue({ id: 3 });
    const fixturePlan = fixture.cases[0].plan;
    const diagrams = fixturePlan.lzs.map((lz) => ({ ...lz.diagram, id: lz.id }));
    const routeSets = fixturePlan.routeSets.map((set) => ({ key: set.id, routes: set.data.routes }));
    const onPresent = jest.fn();
    const Setup = () => {
      const brief = useBrief({ enabled: true, active: true, user: { id: 7, name: "Colin M." }, session: { name: "", diagrams, routeSets } });
      return <BriefSetup brief={brief} title="this session" threatCount={0} onClose={() => {}} onPresent={onPresent} />;
    };
    render(<Setup />);
    await act(async () => {});
    expect(screen.queryByRole("button", { name: "Present sandtable" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /Review/ }));
    fireEvent.click(screen.getByRole("button", { name: "Present sandtable" }));
    expect(onPresent).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "Download PDF" })).toBeInTheDocument();
  });
});
