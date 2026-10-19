import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Icon from "../ui/Icon";
import { WRITE_IN } from "./compile";
import { fitInsets, indexOfStep, sandtableSteps, stepView } from "./sandtableSteps";
import Sentence from "./Sentence";
import "./brief.css";

/*
 * The live sandtable (docs/MISSION_BRIEF.md §2.8; the design's "Try it · live sandtable"): the brief's steps walked on the
 * live map, one at a time. Its bar takes the top bar's place and its steps the sidebar's; over the map, the mission
 * constants (top right) and the script card with who briefs it (bottom). Each step flies the map to its figure and draws
 * it (SandtableLayer); the arrow keys, Page Up / Page Down (a presenter's clicker) and the card's buttons move between
 * steps, Escape or End brief ends it. Only the presenter's map moves, and nothing can be changed while it runs: the
 * planning layers and panels are put away, and no key reaches the rest of the app.
 *
 * The brief is the one compiled now, so a change made to the plan meanwhile (someone else's, in a pack) shows at once; a
 * step is followed by its id, so one that goes leaves the presenter where it was. A step is never called by a PDF page.
 */

const NARROW = "(max-width: 850px)";
const isNarrow = () => {
  try {
    return Boolean(window.matchMedia?.(NARROW).matches);
  } catch {
    return false;
  }
};

const boxOf = (ref) => ref.current?.getBoundingClientRect?.() ?? null;

// A mouse press on a step control does not take the focus, so the space bar goes on stepping forward (a pressed Previous
// would otherwise be pressed again). Tab still reaches them.
const keepFocus = (event) => event.preventDefault();

/** Keys a field, or a dialog over the brief, keeps for itself. */
const keepsItsKeys = (target) =>
  target instanceof Element &&
  Boolean(target.closest('input, textarea, select, [contenteditable=""], [contenteditable="true"], [role="dialog"]'));

/**
 * The sandtable's state: whether it runs (`on`, App's, so the brief can be loaded while it does), the step shown, and the
 * panels. `start` begins at the first step; `end` gives the map and the keys back. While the brief is still `loading` (the
 * one opened last, read from the Library) nothing is shown or flown to.
 */
export const useSandtable = ({ model, on, setOn, loading = false }) => {
  const [stepId, setStepId] = useState(null);
  const [anchor, setAnchor] = useState(0);
  const [stepsOpen, setStepsOpen] = useState(true);
  const [constantsOpen, setConstantsOpen] = useState(true);
  const cardRef = useRef(null);
  const constantsRef = useRef(null);
  const stepsRef = useRef(null);
  const focusBack = useRef(null);

  const steps = useMemo(() => sandtableSteps(model), [model]);
  const index = loading ? -1 : indexOfStep(model, stepId, anchor);
  // The step shown is the one followed, and where it is now is kept: if it goes from the brief the presenter stays at that
  // place in the list, and the step then shown is followed in its turn, never another that later lands at that place.
  useEffect(() => {
    if (index < 0) return;
    if (index !== anchor) setAnchor(index);
    if (steps[index].id !== stepId) setStepId(steps[index].id);
  }, [index, anchor, steps, stepId]);
  const view = useMemo(() => (index >= 0 ? stepView(model, index) : null), [model, index]);
  const geometry = model?.geometry ?? null;
  const drawKey = useMemo(() => JSON.stringify([view, geometry]), [view, geometry]);

  const go = useCallback(
    (i) => {
      if (i < 0 || i >= steps.length) return;
      setStepId(steps[i].id);
      setAnchor(i);
    },
    [steps],
  );
  const next = useCallback(() => go(index + 1), [go, index]);
  const previous = useCallback(() => go(index - 1), [go, index]);

  const start = useCallback(() => {
    focusBack.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setStepId(null);
    setAnchor(0);
    const narrow = isNarrow();
    setStepsOpen(!narrow);
    setConstantsOpen(!narrow);
    setOn(true);
  }, [setOn]);

  const end = useCallback(() => {
    setOn(false);
    const back = focusBack.current;
    focusBack.current = null;
    // Once the planning frame is back: what had the focus before the brief has it again, else the Brief button (the menu
    // item or the setup's button it was begun from is gone by then).
    setTimeout(() => {
      const target = back?.isConnected ? back : document.querySelector(".brief-menu__button");
      target?.focus({ preventScroll: true });
    }, 0);
  }, [setOn]);

  // Nothing else in the app acts on a key while the brief runs: the steps take the arrows, Page Up and Page Down, Home,
  // End and the space bar, Escape ends it, and the planning shortcuts (Ctrl/⌘ S) do nothing. Listened for first, on the
  // window as the key goes down, so Leaflet's own keys and the app's never see them.
  useEffect(() => {
    if (!on) return undefined;
    const onKey = (event) => {
      if (keepsItsKeys(event.target)) return;
      if (event.ctrlKey || event.metaKey || event.altKey) {
        if ((event.ctrlKey || event.metaKey) && String(event.key).toLowerCase() === "s") event.preventDefault();
        event.stopPropagation();
        return;
      }
      let handled = true;
      switch (event.key) {
        case "ArrowRight":
        case "ArrowDown":
        case "PageDown":
          next();
          break;
        case "ArrowLeft":
        case "ArrowUp":
        case "PageUp":
          previous();
          break;
        case "Home":
          go(0);
          break;
        case "End":
          go(steps.length - 1);
          break;
        case " ":
          // On a button the space bar presses it.
          if (event.target instanceof Element && event.target.closest('button, a, [role="button"]')) handled = false;
          else next();
          break;
        case "Escape":
          end();
          break;
        default:
          handled = false;
      }
      if (handled) event.preventDefault();
      event.stopPropagation();
    };
    window.addEventListener("keydown", onKey, true);
    return () => window.removeEventListener("keydown", onKey, true);
  }, [on, next, previous, go, end, steps.length]);

  // How far in from the map's edges a figure is fitted: clear of the card, the constants box, and the steps where they
  // lie over the map (a narrow window).
  const measure = useCallback(
    (mapBox) => fitInsets(mapBox, [boxOf(cardRef), boxOf(constantsRef), boxOf(stepsRef)]),
    [],
  );

  const presentation = useMemo(() => ({ view, geometry, drawKey, measure }), [view, geometry, drawKey, measure]);

  return {
    on,
    start,
    end,
    model,
    steps,
    index,
    current: index >= 0 ? steps[index] : null,
    step: index >= 0 ? model.steps[index] : null,
    go,
    next,
    previous,
    stepsOpen,
    setStepsOpen,
    constantsOpen,
    setConstantsOpen,
    refs: { card: cardRef, constants: constantsRef, steps: stepsRef },
    presentation,
    loading,
  };
};

/** Across the top, in the top bar's place: what is being briefed, where it is, and End brief. */
export const SandtableBar = ({ sandtable, place }) => {
  const { model, current, stepsOpen, setStepsOpen, end } = sandtable;
  const title = [place, model?.template?.shortTitle].filter(Boolean).join(" · ");
  return (
    <header className="sandtable-bar">
      <span className="ui-chip ui-chip--warn sandtable-bar__live"><span className="ui-chip__dot" />Presenting</span>
      <div className="sandtable-bar__titles">
        <h1 className="sandtable-bar__title">{title}</h1>
        <div className="sandtable-bar__sub">Presenter's map · only your map moves · editing is off</div>
      </div>
      <div className="sandtable-bar__end">
        {!stepsOpen && (
          <button type="button" className="ui-btn ui-btn--34" aria-expanded="false" onMouseDown={keepFocus} onClick={() => setStepsOpen(true)}>
            <Icon name="menu" size={15} />
            Steps
          </button>
        )}
        <span className="sandtable-bar__mark">Unclassified</span>
        {current && <span className="sandtable-bar__position">{current.position}</span>}
        <button type="button" className="ui-btn ui-btn--34" onClick={end}>
          <Icon name="x" size={15} />
          End brief
        </button>
      </div>
    </header>
  );
};

/** The steps, in the sidebar's place (over the map on a narrow window): where the brief is, and a way to any step. */
export const SandtableSteps = ({ sandtable }) => {
  const { model, steps, index, go, stepsOpen, setStepsOpen, refs, loading } = sandtable;
  const list = useRef(null);
  useEffect(() => {
    list.current?.querySelector('[aria-current="step"]')?.scrollIntoView?.({ block: "nearest" });
  }, [index, stepsOpen]);
  if (!stepsOpen) return null;
  return (
    <nav className="sandtable-steps" id="sandtable-steps" aria-label="Brief steps" ref={refs.steps}>
      <div className="sandtable-steps__head">
        <span className="ui-label">Steps<span className="ui-label__count">{steps.length}</span></span>
        <button type="button" className="ui-btn ui-btn--ghost ui-btn--28 ui-btn--square" aria-label="Hide steps" aria-controls="sandtable-steps" aria-expanded="true" onMouseDown={keepFocus} onClick={() => setStepsOpen(false)}>
          <Icon name="chevronLeft" size={16} />
        </button>
      </div>
      {loading && <p className="brief-hint sandtable-steps__wait">Opening the brief…</p>}
      <ol className="sandtable-steps__list" ref={list} hidden={loading}>
        {steps.map((s, i) => (
          <li key={s.id}>
            <button
              type="button"
              className="sandtable-steps__step"
              aria-current={i === index ? "step" : undefined}
              onMouseDown={keepFocus}
              onClick={() => {
                go(i);
                // On a narrow window the list lies over the map and the card: the step chosen is what they want to see.
                if (isNarrow()) setStepsOpen(false);
              }}
            >
              <span className="sandtable-steps__n">{s.number}</span>
              <span className="sandtable-steps__title">{model.steps[i].title}</span>
              {s.time && <span className="sandtable-steps__time">{s.time}</span>}
            </button>
          </li>
        ))}
      </ol>
    </nav>
  );
};

/** Over the map: the mission constants (top right) and the script card (bottom). */
export const SandtableStage = ({ sandtable }) => {
  const { model, steps, index, current, step, next, previous, constantsOpen, setConstantsOpen, refs } = sandtable;
  const constants = model?.constants ?? [];
  const nextButton = useRef(null);
  // The brief has the keyboard's focus once it begins: the next step is a press of Enter or the space bar away.
  useEffect(() => {
    nextButton.current?.focus({ preventScroll: true });
  }, []);
  return (
    <>
      {constants.length > 0 && (
        <section className="sandtable-constants" aria-label="Mission constants" ref={refs.constants}>
          <button type="button" className="sandtable-constants__head" aria-expanded={constantsOpen} onMouseDown={keepFocus} onClick={() => setConstantsOpen(!constantsOpen)}>
            <span className="ui-label">Mission constants</span>
            <Icon name={constantsOpen ? "chevronUp" : "chevronDown"} size={14} />
          </button>
          {constantsOpen && (
            <dl className="sandtable-constants__list">
              {constants.map((c) => (
                <div key={c.id}>
                  <dt>{c.label}</dt>
                  <dd className={c.value ? undefined : "brief-writein"}>{c.value ?? WRITE_IN}</dd>
                </div>
              ))}
            </dl>
          )}
        </section>
      )}

      <section className="sandtable-card" aria-label="Step" ref={refs.card}>
        {step ? (
          <>
            <div className="sandtable-card__tags">
              <span className="ui-chip ui-chip--info sandtable-card__section">{step.number} {current.section}</span>
              <span className="ui-chip">{step.who}</span>
              {current.time && <span className="sandtable-card__time">{current.time}</span>}
            </div>
            <h2 className="sandtable-card__title">{current.title}</h2>
            <div className="sandtable-card__body">
              <div className="sandtable-card__script">
                {step.script.length > 0
                  // eslint-disable-next-line react/no-array-index-key
                  ? step.script.map((parts, i) => <Sentence key={i} parts={parts} />)
                  : <p className="brief-hint">Nothing to say for this step.</p>}
              </div>
              {step.facts.length > 0 && (
                <dl className="sandtable-card__facts">
                  {step.facts.map((f) => (
                    <div key={f.key}>
                      <dt>{f.label}</dt>
                      <dd className={f.value ? undefined : "brief-writein"}>{f.value ?? WRITE_IN}</dd>
                    </div>
                  ))}
                </dl>
              )}
            </div>
          </>
        ) : (
          <p className="brief-hint">{sandtable.loading ? "Opening the brief…" : "This brief has no steps."}</p>
        )}
        <div className="sandtable-card__nav">
          <button type="button" className="sandtable-card__arrow" aria-label="Previous step" disabled={index <= 0} onMouseDown={keepFocus} onClick={previous}>
            <Icon name="chevronLeft" size={18} />
          </button>
          <div className="sandtable-card__progress" aria-hidden="true">
            {steps.map((s, i) => (
              <span key={s.id} className={i < index ? "is-done" : i === index ? "is-current" : undefined} />
            ))}
          </div>
          <span className="sandtable-card__keys" aria-hidden="true"><kbd className="ui-kbd">←</kbd><kbd className="ui-kbd">→</kbd> keys</span>
          <button type="button" ref={nextButton} className="sandtable-card__arrow sandtable-card__arrow--next" aria-label="Next step" disabled={index < 0 || index >= steps.length - 1} onClick={next}>
            <Icon name="chevronRight" size={18} />
          </button>
        </div>
        <div className="ui-visually-hidden" aria-live="polite">{current ? `${current.position}: ${step.title}` : ""}</div>
      </section>
    </>
  );
};
