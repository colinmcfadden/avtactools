import React from "react";
import "./ui.css";

/*
 * A person as their initials in a circle. The same person is the same colour everywhere (by their id,
 * else their name), from a palette that reads on the dark panels.
 */

const COLORS = ["#5aa7d4", "#e8a25c", "#6fd0a8", "#d98bc3", "#9db8e8", "#e6b8a0", "#c7d36f", "#a89ae6"];

export const initials = (name) => {
  const words = String(name ?? "").trim().split(/[\s@._-]+/).filter(Boolean);
  if (words.length === 0) return "?";
  if (words.length === 1) return words[0].slice(0, 2).toUpperCase();
  return (words[0][0] + words[words.length - 1][0]).toUpperCase();
};

export const colorFor = (key) => {
  let hash = 0;
  for (const ch of String(key ?? "")) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0;
  return COLORS[hash % COLORS.length];
};

/** "Sam B." for "Sam Bell": how the history and the cards name people. */
export const shortName = (name) => {
  const words = String(name ?? "").trim().split(/\s+/).filter(Boolean);
  if (words.length < 2) return words[0] ?? "Someone";
  return `${words[0]} ${words[words.length - 1][0]}.`;
};

const Avatar = ({ person, size, online = false, title }) => {
  const name = person?.name || person?.email || "";
  const key = person?.id ?? person?.user_id ?? name;
  return (
    <span
      className={`ui-avatar${size ? ` ui-avatar--${size}` : ""}`}
      style={{ background: colorFor(key) }}
      title={title ?? name}
      aria-hidden={title === "" ? "true" : undefined}
    >
      {initials(name)}
      {online && <span className="ui-avatar__online" />}
    </span>
  );
};

export default Avatar;

/** A few people overlapping, then "+n". */
export const AvatarStack = ({ people, max = 3, size }) => {
  const shown = (people ?? []).slice(0, max);
  const more = (people ?? []).length - shown.length;
  return (
    <span className="ui-avatars">
      {shown.map((person) => (
        <Avatar key={person.id ?? person.user_id ?? person.session ?? person.name} person={person} size={size} />
      ))}
      {more > 0 && (
        <span className={`ui-avatar${size ? ` ui-avatar--${size}` : ""}`} style={{ background: "#2b313b", color: "#e7eaee" }}>
          +{more}
        </span>
      )}
    </span>
  );
};
