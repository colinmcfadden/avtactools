/*
 * Times as the redesign writes them, in the person's own time zone: "14:02" today, "Oct 3" this year,
 * "Oct 3, 2025" before; "4 min ago" for what just happened. Hours are 24-hour, as crews read them.
 */

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

const at = (value) => {
  if (value === null || value === undefined || value === "") return null;
  // The API writes naive UTC times ("2026-10-07T12:00:00"): read them as UTC, not local.
  const text = typeof value === "string" && /T\d\d:\d\d(:\d\d(\.\d+)?)?$/.test(value) ? `${value}Z` : value;
  const date = new Date(text);
  return Number.isNaN(date.getTime()) ? null : date;
};

const pad = (n) => String(n).padStart(2, "0");

export const clockTime = (value) => {
  const date = at(value);
  return date ? `${pad(date.getHours())}:${pad(date.getMinutes())}` : "";
};

export const shortDate = (value, now = new Date()) => {
  const date = at(value);
  if (!date) return "";
  const day = `${MONTHS[date.getMonth()]} ${date.getDate()}`;
  return date.getFullYear() === now.getFullYear() ? day : `${day}, ${date.getFullYear()}`;
};

const sameDay = (a, b) => a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();

/** "14:02" if today, else "Oct 3". */
export const whenShort = (value, now = new Date()) => {
  const date = at(value);
  if (!date) return "";
  return sameDay(date, now) ? clockTime(date) : shortDate(date, now);
};

/** "Aug 25 · 17:34". */
export const dateAndTime = (value, now = new Date()) => {
  const date = at(value);
  return date ? `${shortDate(date, now)} · ${clockTime(date)}` : "";
};

/** "Aug 25 at 17:34". */
export const dateAtTime = (value, now = new Date()) => {
  const date = at(value);
  return date ? `${shortDate(date, now)} at ${clockTime(date)}` : "";
};

/** "just now", "4 min ago", "2 h ago", else the date. */
export const timeAgo = (value, now = new Date()) => {
  const date = at(value);
  if (!date) return "";
  const minutes = Math.round((now - date) / 60000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  return shortDate(date, now);
};

export const parseTime = at;
