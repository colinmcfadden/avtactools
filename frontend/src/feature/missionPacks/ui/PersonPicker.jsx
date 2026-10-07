import React, { useEffect, useId, useRef, useState } from "react";
import Avatar from "../../ui/Avatar";
import Icon from "../../ui/Icon";
import * as packApi from "../packApi";
import "./packs.css";

/*
 * Finds someone to invite. A name finds only people on the person's teams (the server has no open
 * directory: every account is an aviator's); anyone else is reached by typing their whole email
 * address. Picking calls `onPick({ user })` or `onPick({ email })`.
 */

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const PersonPicker = ({ onPick, exclude = [], search = packApi.searchPeople, label = "Name or email", placeholder = "Search your teams, or type an email" }) => {
  const id = useId();
  const [text, setText] = useState("");
  const [found, setFound] = useState([]);
  const [cursor, setCursor] = useState(0);
  const input = useRef(null);
  const query = text.trim();
  const isEmail = EMAIL.test(query);

  useEffect(() => {
    if (query.length < 2 || isEmail) {
      setFound([]);
      return undefined;
    }
    let current = true;
    const timer = setTimeout(() => {
      search(query).then(
        (answer) => current && setFound((answer?.users ?? []).filter((u) => !exclude.includes(u.id))),
        () => current && setFound([]),
      );
    }, 250);
    return () => {
      current = false;
      clearTimeout(timer);
    };
    // `exclude` is read when the answer comes; it must not restart the search.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, isEmail, search]);

  const options = isEmail ? [{ email: query.toLowerCase() }] : found.map((user) => ({ user }));
  const choose = (option) => {
    if (!option) return;
    onPick(option);
    setText("");
    setFound([]);
    setCursor(0);
    input.current?.focus();
  };

  return (
    <div className="ui-field" style={{ position: "relative" }}>
      <label className="ui-field__label" htmlFor={id}>{label}</label>
      <input
        ref={input}
        id={id}
        className="ui-input ui-input--36"
        type="text"
        autoComplete="off"
        spellCheck={false}
        placeholder={placeholder}
        value={text}
        role="combobox"
        aria-expanded={options.length > 0}
        aria-controls={`${id}-list`}
        aria-autocomplete="list"
        onChange={(event) => {
          setText(event.target.value);
          setCursor(0);
        }}
        onKeyDown={(event) => {
          if (event.key === "ArrowDown" && options.length) {
            event.preventDefault();
            setCursor((c) => (c + 1) % options.length);
          } else if (event.key === "ArrowUp" && options.length) {
            event.preventDefault();
            setCursor((c) => (c - 1 + options.length) % options.length);
          } else if (event.key === "Enter") {
            event.preventDefault();
            choose(options[cursor]);
          }
        }}
      />
      {options.length > 0 && (
        <div className="packs-suggest" role="listbox" id={`${id}-list`}>
          {options.map((option, index) => (
            <button
              key={option.user ? option.user.id : option.email}
              type="button"
              role="option"
              aria-selected={index === cursor}
              className="packs-suggest__row"
              onMouseDown={(event) => event.preventDefault()}
              onClick={() => choose(option)}
            >
              {option.user ? <Avatar person={option.user} size="sm" /> : <Icon name="mail" size={15} />}
              <span style={{ flex: 1, minWidth: 0 }}>
                <span className="packs-person__name" style={{ display: "block" }}>{option.user ? option.user.name || option.user.email : `Invite ${option.email}`}</span>
                <span className="packs-person__meta" style={{ display: "block" }}>{option.user ? option.user.email : "They get an email with a link to join"}</span>
              </span>
            </button>
          ))}
        </div>
      )}
      <div className="ui-field__hint">
        Name search only finds people on your teams. To invite anyone else, type their full email address.
      </div>
    </div>
  );
};

export default PersonPicker;
