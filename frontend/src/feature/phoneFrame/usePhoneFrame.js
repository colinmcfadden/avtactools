import { useCallback, useEffect, useRef, useState } from "react";
import { DISMISSED_BY_A_TAP, frameHidden, frameTap } from "./phoneFrame";

/*
 * The phone's frame as the person has it: put away by a tap on the map (everything but the search bar) and brought
 * back by the next, its tools folded and unfolded by the button under them, and the menu under the search bar open or
 * closed. The rules are phoneFrame.js's; this holds what they are asked about.
 *
 * `step` names where the LZ/PZ stands (which one, and how far along): a new step offers new tools, so they are shown.
 */

const HIDDEN = "Tools hidden. Tap the map, or press Menu, to show them.";
const SHOWN = "Tools shown.";

// What the open menu is made of, and what may be opened from it: a press on any of these leaves it open.
const MENU_PARTS = ".phone-menu, .phone-bar__menu, .ui-menu, .ui-dialog__backdrop";

export const usePhoneFrame = ({ phone = false, drawing = false, presenting = false, step = "" } = {}) => {
  const [away, setAway] = useState(false);
  const [toolsFolded, setToolsFolded] = useState(false);
  const [menu, setMenu] = useState(false);
  // Said to a screen reader as the frame goes and comes back: nothing on the screen says it otherwise.
  const [status, setStatus] = useState("");

  const hidden = frameHidden({ phone, hidden: away, drawing, presenting });
  const menuOpen = phone && !presenting && menu && !hidden;

  const stepSeen = useRef(step);
  useEffect(() => {
    if (stepSeen.current === step) return;
    stepSeen.current = step;
    setAway(false);
    setToolsFolded(false);
  }, [step]);

  // What is drawn needs its buttons, and a wide window has no frame to hide: neither comes back hidden.
  useEffect(() => {
    if (drawing || presenting || !phone) setAway(false);
    if (presenting || !phone) setMenu(false);
  }, [drawing, presenting, phone]);

  const awayNow = useRef(away);
  awayNow.current = away;
  const show = useCallback(() => {
    if (awayNow.current) setStatus(SHOWN);
    setAway(false);
  }, []);
  const toggle = useCallback(() => {
    setStatus(awayNow.current ? SHOWN : HIDDEN);
    setAway(!awayNow.current);
    setMenu(false);
  }, []);
  const closeMenu = useCallback(() => setMenu(false), []);
  // Only a phone has the menu: on a wide window the top bar is there, and asking for it does nothing.
  const phoneNow = useRef(phone);
  phoneNow.current = phone;
  const openMenu = useCallback(() => {
    if (!phoneNow.current) return;
    show();
    setMenu(true);
  }, [show]);
  // The menu button is part of the search bar, which never goes: with the frame away it brings the frame back too.
  const toggleMenu = useCallback(() => {
    if (hidden) openMenu();
    else setMenu((open) => !open);
  }, [hidden, openMenu]);
  const toggleTools = useCallback(() => setToolsFolded((folded) => !folded), []);

  // Escape brings the frame back: the way for a keyboard, which has no tap.
  useEffect(() => {
    if (!hidden) return undefined;
    const onKey = (event) => {
      if (event.key === "Escape") show();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [hidden, show]);

  // The open menu closes on Escape and on a press anywhere but it, its button, and what was opened from it.
  useEffect(() => {
    if (!menuOpen) return undefined;
    const onKey = (event) => {
      if (event.key === "Escape") setMenu(false);
    };
    const onPress = (event) => {
      if (!event.target.closest?.(MENU_PARTS)) setMenu(false);
    };
    window.addEventListener("keydown", onKey);
    document.addEventListener("pointerdown", onPress);
    return () => {
      window.removeEventListener("keydown", onKey);
      document.removeEventListener("pointerdown", onPress);
    };
  }, [menuOpen]);

  // A tap is judged by how things stood as the finger came down (MapTap asks then): by the time the click
  // arrives the search has folded and a menu has closed, and the tap that did that must do nothing more.
  const now = useRef({});
  now.current = { phone, presenting, drawing, menuOpen };
  const beforeTap = useCallback(
    () => ({
      ...now.current,
      searchOpen: Boolean(document.querySelector(".topbar-spotlight--open")),
      dismissed: Boolean(document.querySelector(DISMISSED_BY_A_TAP)),
    }),
    [],
  );
  const onTap = useCallback(
    (stood) => {
      const what = frameTap(stood ?? now.current);
      if (what === "menu") closeMenu();
      else if (what === "frame") toggle();
    },
    [closeMenu, toggle],
  );

  return { hidden, toolsFolded, menuOpen, status, show, toggle, toggleTools, openMenu, closeMenu, toggleMenu, beforeTap, onTap };
};

export default usePhoneFrame;
