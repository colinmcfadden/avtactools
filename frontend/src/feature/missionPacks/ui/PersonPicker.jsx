import React, { useEffect, useId, useImperativeHandle, useRef, useState } from "react";
import Avatar from "../../ui/Avatar";
import Icon from "../../ui/Icon";
import * as packApi from "../packApi";
import "./packs.css";

/*
 * Finds someone to invite. A name finds only people on the person's teams (the server has no open
 * directory: every account is an aviator's); anyone else is reached by typing their whole email
 * address. Picking calls `onPick({ user })` or `onPick({ email })`.
 *
 * With `select`, picking a suggestion only chooses the person (their name fills the field) and nothing
 * is sent yet: the caller commits, with its own button or on Enter (`onPick`), and clears the field with
 * `handle.current.clear()` once it has. `onChange` hears the choice as it changes ({ user }, { email }
 * or null), so the button knows when there is something to send. The Members dialog works this way, so
 * the role beside the field is the one chosen, not whichever happened to be set when a name was clicked.
 */

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const PersonPicker = ({
  onPick,
  onChange,
  select = false,
  handle,
  exclude = [],
  search = packApi.searchPeople,
  label = "Name or email",
  placeholder = "Search your teams, or type an email",
}) => {
  const id = useId();
  const [text, setText] = useState("");
  const [found, setFound] = useState([]);
  const [cursor, setCursor] = useState(0);
  const [chosen, setChosen] = useState(null);
  const input = useRef(null);
  const query = text.trim();
  const isEmail = EMAIL.test(query);

  useEffect(() => {
    // A person chosen fills the field with their name: that is not a search.
    if (chosen || query.length < 2 || isEmail) {
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
  }, [query, isEmail, search, chosen]);

  const clear = () => {
    setText("");
    setFound([]);
    setCursor(0);
    setChosen(null);
  };
  useImperativeHandle(handle, () => ({ clear, focus: () => input.current?.focus() }), []);

  // What would be sent now: the person chosen, or a whole email address typed.
  const choice = chosen ? { user: chosen } : isEmail ? { email: query.toLowerCase() } : null;
  const choiceKey = chosen ? `user:${chosen.id}` : isEmail ? `email:${query.toLowerCase()}` : "";
  useEffect(() => {
    onChange?.(choice);
    // Told when the choice changes, not on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [choiceKey]);

  // Choosing, an email needs no suggestion: the field already holds it, and the caller sends it.
  let options = found.map((user) => ({ user }));
  if (select && chosen) options = [];
  else if (!select && isEmail) options = [{ email: query.toLowerCase() }];
  const choose = (option) => {
    if (!option) return;
    if (select) {
      setChosen(option.user);
      setText(option.user.name || option.user.email);
      setFound([]);
      setCursor(0);
      input.current?.focus();
      return;
    }
    onPick(option);
    clear();
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
        aria-describedby={`${id}-hint`}
        onChange={(event) => {
          setText(event.target.value);
          setCursor(0);
          setChosen(null);
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
            // A list open: Enter takes the suggestion it is on. Choosing, with none open, it sends.
            if (options.length) choose(options[cursor]);
            else if (select && choice) onPick?.(choice);
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
      <div id={`${id}-hint`} className="ui-field__hint">
        {chosen
          ? `${chosen.name && chosen.email ? `${chosen.name}, ${chosen.email}` : chosen.name || chosen.email}. Type to look for someone else.`
          : "Name search only finds people on your teams. To invite anyone else, type their full email address."}
      </div>
    </div>
  );
};

export default PersonPicker;
