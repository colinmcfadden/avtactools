/*
 * Find in a document (docs/DOCUMENTS.md §8.2): where a query occurs in a document's page text, the text the server read once at upload
 * (`{version, pages}`), so every client finds the same hits the same way, offline too. Pure and without imports: the native apps port it
 * and are held to contracts/fixtures/documents/search.json (frontend/src/contracts/documentSearchFixtures.test.js).
 *
 * The rule. The query and the text are each folded one code point at a time: Unicode NFKD, its combining marks (\p{M}) taken out, then
 * each code point lower-cased alone (so no context, such as a final sigma, changes a letter), and a run of white space
 * (\p{White_Space}) one space. A hit is where the folded query occurs in the folded text, scanning from the start, the next search
 * starting after the hit (hits never overlap). Each hit is given in the page's own text, by code point: where it starts and ends, and
 * up to `context` code points either side of it, cut where the page starts or ends. A query that folds to nothing, or to a space alone,
 * finds nothing.
 */

const MARK = /\p{M}/u;
const SPACE = /\p{White_Space}/u;

/** One code point folded: zero or more code points (a mark is none, a ligature several), white space as " ". */
const foldCodePoint = (cp) => {
  if (SPACE.test(cp)) return [" "];
  const out = [];
  for (const part of cp.normalize("NFKD")) {
    if (MARK.test(part)) continue;
    for (const lower of part.toLowerCase()) {
      if (!MARK.test(lower)) out.push(SPACE.test(lower) ? " " : lower);
    }
  }
  return out;
};

/**
 * `text` folded, with where each folded code point came from: `chars` (code points) and `from` (each one's index in the text's code
 * points). A run of white space is one space, taken from the run's first.
 */
const foldWithOrigins = (text) => {
  const chars = [];
  const from = [];
  let index = 0;
  for (const cp of text) {
    for (const folded of foldCodePoint(cp)) {
      if (folded === " " && chars[chars.length - 1] === " ") continue;
      chars.push(folded);
      from.push(index);
    }
    index += 1;
  }
  return { chars, from };
};

/** `text` folded as the rule says (a string). */
export const foldForSearch = (text) => foldWithOrigins(String(text ?? "")).chars.join("");

/** The folded query, its runs of space one space and with none at either end. */
const foldQuery = (query) => foldForSearch(query).trim();

const sameAt = (chars, at, query) => {
  for (let i = 0; i < query.length; i += 1) {
    if (chars[at + i] !== query[i]) return false;
  }
  return true;
};

/**
 * Every hit of `query` in `pages` (each page's text), page by page: `{ hits, count, more }`, where each hit is
 * `{ page (from 1), start, end, before, match, after, cutBefore, cutAfter }` (`start` and `end` in the page's code points, `end` past
 * the hit's last; `cutBefore` / `cutAfter` when the page goes on beyond `before` / `after`). `count` is every hit; at most `limit` are
 * listed, and `more` says some were not.
 */
export const findInDocument = (pages, query, { context = 40, limit = 1000 } = {}) => {
  const wanted = [...foldQuery(query)];
  const result = { hits: [], count: 0, more: false };
  if (!wanted.length || !Array.isArray(pages)) return result;
  pages.forEach((pageText, pageIndex) => {
    const text = typeof pageText === "string" ? pageText : "";
    if (!text) return;
    const { chars, from } = foldWithOrigins(text);
    let points = null;
    for (let at = 0; at + wanted.length <= chars.length; ) {
      if (!sameAt(chars, at, wanted)) {
        at += 1;
        continue;
      }
      result.count += 1;
      if (result.hits.length < limit) {
        points = points ?? [...text];
        const start = from[at];
        const end = from[at + wanted.length - 1] + 1;
        const beforeStart = Math.max(0, start - context);
        const afterEnd = Math.min(points.length, end + context);
        result.hits.push({
          page: pageIndex + 1,
          start,
          end,
          before: points.slice(beforeStart, start).join(""),
          match: points.slice(start, end).join(""),
          after: points.slice(end, afterEnd).join(""),
          cutBefore: beforeStart > 0,
          cutAfter: afterEnd < points.length,
        });
      } else {
        result.more = true;
      }
      at += wanted.length;
    }
  });
  return result;
};
