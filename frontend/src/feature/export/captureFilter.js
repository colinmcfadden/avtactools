/**
 * What the LZ card capture (ExportHandler, through html-to-image) leaves out of the map:
 * Leaflet's controls, the app's panels, and any layer marked SCREEN_ONLY_CLASS. The MGRS grid
 * is one: the owner wants it on the screen and never on a card, whether it is on or off
 * (2026-10-08).
 */
export const SCREEN_ONLY_CLASS = "map-screen-only";

const LEFT_OUT = ["leaflet-control-container", "ff-panel", SCREEN_ONLY_CLASS];

/** html-to-image's filter: false for a node the card must not show, and so for all inside it. */
export const keepInCapture = (node) => !LEFT_OUT.some((name) => node.classList?.contains(name));
