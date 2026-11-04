/**
 * A grid sent to someone (docs/GRID_SHARING.md §1): the same few lines whatever carries them (the system's
 * share sheet, a QR code, a copy), and a link that opens EZ/PZ at the grid.
 *
 *   LZ HAWK
 *   16S GC 28864 55349
 *   34.54568° N, 84.12346° W
 *   https://ezpztac.app/#grid=16SGC2886455349&name=LZ%20HAWK
 *
 * The grid goes after a `#`: a fragment is never sent to a server, so the place stays out of the site's logs.
 * Android's `GridShare` is a port, held to contracts/fixtures/coords/share.json.
 */
import { formatLatLong, readTarget } from "./targetReading";

export const GRID_LINK_BASE = "https://ezpztac.app/";
export const LABEL_MAX = 60;

// Control characters and line breaks: a label is one line of the person's own words.
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u001f\u007f-\u009f\u2028\u2029]/g;

/**
 * A label as it is shared: one line, spaces collapsed, at most 60 characters counted as characters (an emoji
 * is one, and is never split), or "" for none.
 */
export const cleanLabel = (label) => {
  const text = String(label ?? "")
    .replace(CONTROL, " ")
    // Half of an emoji on its own (a name cut elsewhere) cannot be put in a link: encodeURIComponent throws on it.
    .replace(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/g, "")
    .replace(/\s+/g, " ")
    .trim();
  return Array.from(text).slice(0, LABEL_MAX).join("").trim();
};

/** The link that opens EZ/PZ at `mgrs` (spaces taken out), with its label when there is one. */
export const gridLink = (mgrs, label) => {
  const grid = String(mgrs ?? "").replace(/\s/g, "").toUpperCase();
  const name = cleanLabel(label);
  return `${GRID_LINK_BASE}#grid=${encodeURIComponent(grid)}${name ? `&name=${encodeURIComponent(name)}` : ""}`;
};

/** The lines a shared grid is, joined by line breaks: the label (when there is one), the grid, the lat/long, the link. */
export const shareText = ({ mgrs, lat, lon, label }) => {
  const name = cleanLabel(label);
  return [name, mgrs, formatLatLong(lat, lon), gridLink(mgrs, name)].filter(Boolean).join("\n");
};

/**
 * What a link's fragment (`#grid=…&name=…`) asks for: `{ choice, name }`, the grid read as the target field
 * reads it (so a grid that is not on the map is refused) and the label cleaned, or null when the fragment has
 * no grid or its grid does not read as one place.
 */
export const readGridLink = (hash) => {
  const fragment = String(hash ?? "").replace(/^#/, "");
  if (!fragment) return null;
  let params;
  try {
    params = new URLSearchParams(fragment);
  } catch {
    return null;
  }
  const grid = params.get("grid");
  if (!grid || grid.length > 40) return null;
  const { choices } = readTarget(grid);
  // A link names a grid, never a lat/long to guess a side for: exactly one place, and a grid at that.
  if (choices.length !== 1 || !choices[0].how.startsWith("MGRS")) return null;
  return { choice: choices[0], name: cleanLabel(params.get("name")) };
};
