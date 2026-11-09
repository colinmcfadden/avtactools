import { formatLatLong } from "../../utils/targetReading";
import { toMgrs } from "../../utils/mgrs";

/*
 * A place from the search (POST /api/places/search) as the target field shows it and as it is chosen. Names are the
 * sources' own: the FAA's in its capitals, as the chart and the A/FD spell them; USGS's (GNIS) in mixed case.
 */

/** "DZJ · BLAIRSVILLE"; a fix, which has only its identifier, "BOBBY". */
export const placeTitle = (place) => {
  const name = place.name && place.name !== place.ident ? place.name : "";
  return [place.ident, name].filter(Boolean).join(" · ") || "Unnamed place";
};

/** How the place is named where it is said in a sentence (a toast, a shared grid): "BLAIRSVILLE (DZJ)". */
export const placeLabel = (place) => {
  const name = place.name && place.name !== place.ident ? place.name : "";
  if (name && place.ident) return `${name} (${place.ident})`;
  return name || place.ident || "";
};

const thousands = (value) => String(Math.round(value)).replace(/\B(?=(\d{3})+(?!\d))/g, ",");

// GNIS's county name, said as a crew would: Louisiana's are parishes; Alaska's are boroughs and census areas, which GNIS
// names as they are ("Nome (CA)"), and so are Virginia's independent cities ("Suffolk (city)") and the territories'.
const NO_COUNTY_WORD = new Set(["AK", "DC", "PR", "GU", "AS", "MP", "VI"]);
export const countyWords = (county, state) => {
  if (!county) return "";
  if (county.includes("(") || NO_COUNTY_WORD.has(state)) return county;
  return `${county} ${state === "LA" ? "Parish" : "County"}`;
};

/** "Heliport, private · BLAIRSVILLE, GA · 1,900 ft"; a GNIS name, "Summit · Towns County, GA". */
export const placeNote = (place) => {
  const where = [place.city || countyWords(place.county, place.state), place.state].filter(Boolean).join(", ");
  const elevation = Number.isFinite(place.elevationFt) ? `${thousands(place.elevationFt)} ft` : "";
  return [place.kindWords, where, elevation].filter(Boolean).join(" · ");
};

const NM_M = 1852;
const EARTH_M = 6371008.8;
const rad = (degrees) => (degrees * Math.PI) / 180;

/** How far the place is from `near` ({ lat, lon }), in words, as crews measure it: "12 nm", "<1 nm"; or "". */
export const placeDistance = (place, near) => {
  if (!near || !Number.isFinite(near.lat) || !Number.isFinite(near.lon)) return "";
  const dLat = rad(place.lat - near.lat);
  const dLon = rad(place.lon - near.lon);
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(rad(near.lat)) * Math.cos(rad(place.lat)) * Math.sin(dLon / 2) ** 2;
  const nm = (2 * EARTH_M * Math.asin(Math.min(1, Math.sqrt(a)))) / NM_M;
  if (nm < 1) return "<1 nm";
  return `${thousands(nm)} nm`;
};

// How close the map goes: an airport's field fills the view at 14, and so does a town or an installation; a pad, a
// navaid, a fix or a named feature (a summit, a gap) is a point, shown closer.
const ZOOMS = {
  airport: 14, seaplane_base: 14, gliderport: 14, ultralight: 14, balloonport: 14,
  populated_place: 14, military: 14,
};
export const placeZoom = (place) => ZOOMS[place.kind] ?? 15;

/** Where the places come from, as the list credits them: "FAA NASR 2026-10-29 · USGS GNIS 2026-11-02"; or "". */
export const placeSources = (sources) => [
  sources?.nasrCycle ? `FAA NASR ${sources.nasrCycle}` : "",
  sources?.gnisDate ? `USGS GNIS ${sources.gnisDate}` : "",
].filter(Boolean).join(" · ");

/**
 * The field's choice for a place, as a grid or a lat/long's choice is (`{ lat, lon, mgrs, latLong, how }`), with the
 * place and its label beside it; null for a place off the MGRS grid.
 */
export const placeChoice = (place) => {
  if (!Number.isFinite(place?.lat) || !Number.isFinite(place?.lon)) return null;
  const mgrs = toMgrs(place.lat, place.lon);
  if (!mgrs) return null;
  return {
    lat: place.lat,
    lon: place.lon,
    mgrs,
    latLong: formatLatLong(place.lat, place.lon),
    how: place.kindWords,
    place,
    label: placeLabel(place),
  };
};
