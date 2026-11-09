/*
 * Reading an XML file somebody sent (a GPX, a Garmin flight plan, a KML). Such a file comes from
 * whoever sent it, so it is read as hostile input, the way Android's mission reader reads an .msnx
 * (AGENTS.md §17): nothing in these formats needs a document type, and one is how an entity bomb
 * gets in, so a document that declares one is refused by its text before any parser sees it.
 */

export const NOT_XML = "This file could not be read: it is not well-formed XML.";
export const DECLARES_DTD = "This file declares a document type, which these files never need, so it was not read.";

/** The text of an XML file's bytes: UTF-16 by its byte order mark, else UTF-8 (a UTF-8 mark is dropped). */
export const decodeXml = (bytes) => {
  if (bytes[0] === 0xff && bytes[1] === 0xfe) return new TextDecoder("utf-16le").decode(bytes);
  if (bytes[0] === 0xfe && bytes[1] === 0xff) return new TextDecoder("utf-16be").decode(bytes);
  return new TextDecoder("utf-8").decode(bytes);
};

// Walked in one pass, whichever opens first: stripping the comments and then the CDATA (or the other
// way) lets a file hide a real declaration between one that opens inside the other.
const MARKUP = /<!--[\s\S]*?-->|<!\[CDATA\[[\s\S]*?\]\]>|<\?[\s\S]*?\?>|<!\s*(DOCTYPE|ENTITY)/gi;

/** Whether the text declares a document type or an entity. A description quoting `<!DOCTYPE` in a CDATA section does not. */
export const declaresDtd = (text) => {
  MARKUP.lastIndex = 0;
  for (let match = MARKUP.exec(text); match; match = MARKUP.exec(text)) {
    if (match[1]) return true;
  }
  return false;
};

/** The document in `text`, or an Error in words. */
export const parseHostileXml = (text) => {
  if (declaresDtd(text)) throw new Error(DECLARES_DTD);
  let doc;
  try {
    doc = new DOMParser().parseFromString(text.replace(/^﻿/, ""), "application/xml");
  } catch {
    throw new Error(NOT_XML);
  }
  if (!doc?.documentElement || doc.getElementsByTagName("parsererror").length > 0) throw new Error(NOT_XML);
  // The text check is the one that must hold; this one costs nothing.
  if (doc.doctype) throw new Error(DECLARES_DTD);
  return doc;
};

// Files in the wild use several namespaces for one format, or none, so an element goes by its local name.
const named = (element, names) => names.includes(element.localName);

export const childrenNamed = (element, ...names) => Array.from(element?.children ?? []).filter((child) => named(child, names));

export const childNamed = (element, ...names) => childrenNamed(element, ...names)[0] ?? null;

/** The trimmed text of a child element, "" where it has none. */
export const childText = (element, ...names) => (childNamed(element, ...names)?.textContent ?? "").trim();

/**
 * A number written as a number. `Number("")` is 0 and `Number("0x10")` is 16, and a position made up
 * of either would be drawn somewhere nobody put it, so anything else is null.
 */
export const strictNumber = (text) => {
  const value = String(text ?? "").trim();
  if (!/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(value)) return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
};

/** `[lat, lon]` from their text, or null unless both are numbers in range. */
export const readPosition = (latText, lonText) => {
  const lat = strictNumber(latText);
  const lon = strictNumber(lonText);
  if (lat === null || lon === null || Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
  return [lat, lon];
};
