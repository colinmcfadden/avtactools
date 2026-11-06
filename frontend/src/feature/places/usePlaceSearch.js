import { useEffect, useRef, useState } from "react";
import api from "../auth/api";

/*
 * The place search as the target field types (docs/PLACE_SEARCH.md §6): `POST /api/places/search`, asked once the
 * typing pauses, the latest answer only. The text goes in the body, never the address; where the map is goes rounded
 * to a tenth of a degree (about 10 km), which is all the server uses it for (ordering, nearest first).
 */

export const PLACE_DEBOUNCE_MS = 150;
// As many as the field shows expanded; it shows the first few until asked for more.
export const PLACE_LIMIT = 20;
const CACHE_SIZE = 40;

/** The server's answer for `q` near `near` ({ lat, lon } or null): `{ available, places }`. */
export const searchPlaces = async (q, near, limit = PLACE_LIMIT) => {
  const { data } = await api.post("/places/search", { q, near, limit });
  return data;
};

const tenth = (value) => Math.round(value * 10) / 10;
const nearKeyOf = (near) =>
  near && Number.isFinite(near.lat) && Number.isFinite(near.lon) ? `${tenth(near.lat)},${tenth(near.lon)}` : "";
const nearOf = (key) => {
  if (!key) return null;
  const [lat, lon] = key.split(",").map(Number);
  return { lat, lon };
};

const EMPTY = Object.freeze([]);

/**
 * The places `text` could name, while `kind` (placeQuery.js) says it could name one and `search` is given.
 *
 * `places` are those of the latest answer, kept while the next is asked so the list does not flicker as each key is
 * typed; `current` says whether they are the answer for the text as it is now. `failed` is a search that did not
 * answer; `available` false is a server with no index yet. `sources` are the answer's NASR cycle and GNIS date
 * (`{ nasrCycle, gnisDate }`, either null), or null before an answer.
 */
export const usePlaceSearch = ({ text, kind, near, search }) => {
  const q = kind && search ? String(text ?? "").trim() : "";
  const nearKey = nearKeyOf(near);
  const [answer, setAnswer] = useState(null);
  const latest = useRef(0);
  const cache = useRef(new Map());

  useEffect(() => {
    // Every change counts, so an answer for text typed before never lands after one for the text now.
    const mine = ++latest.current;
    if (!q) return undefined;
    const key = `${q.toLowerCase()}|${nearKey}`;
    const kept = cache.current.get(key);
    if (kept) {
      setAnswer({ q, ...kept });
      return undefined;
    }
    const timer = setTimeout(async () => {
      let found;
      try {
        const body = await search(q, nearOf(nearKey), PLACE_LIMIT);
        found = {
          places: Array.isArray(body?.places) ? body.places : EMPTY,
          available: body?.available !== false,
          failed: false,
          sources: { nasrCycle: body?.nasrCycle ?? null, gnisDate: body?.gnisDate ?? null },
        };
        cache.current.set(key, found);
        if (cache.current.size > CACHE_SIZE) cache.current.delete(cache.current.keys().next().value);
      } catch {
        found = { places: EMPTY, available: true, failed: true, sources: null };
      }
      if (mine === latest.current) setAnswer({ q, ...found });
    }, PLACE_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [q, nearKey, search]);

  if (!q) return { places: EMPTY, current: false, failed: false, available: true, sources: null };
  const current = answer?.q === q;
  return {
    places: answer?.places ?? EMPTY,
    current,
    failed: current && answer.failed,
    available: !current || answer.available,
    sources: answer?.sources ?? null,
  };
};
