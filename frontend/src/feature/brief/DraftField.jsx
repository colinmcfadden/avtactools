import React, { useEffect, useRef, useState } from "react";

/*
 * A text field for one of the brief's answers. What is typed is kept here and committed when the person stops typing
 * (`delay`), leaves the field or presses Enter (a single line), so one sentence is one change: in a pack, one line of
 * its history. A value changed under the field (someone else in the pack, a reset) is shown unless the field has the
 * focus, where it would move the text out from under the cursor.
 */
const DraftField = ({ value, onCommit, multiline = false, delay = 600, className = "ui-input", ...rest }) => {
  const [text, setText] = useState(value ?? "");
  const focused = useRef(false);
  const timer = useRef(null);
  const latest = useRef({ text, value, onCommit });
  latest.current = { text, value: value ?? "", onCommit };

  useEffect(() => {
    if (!focused.current) setText(value ?? "");
  }, [value]);

  const commit = () => {
    clearTimeout(timer.current);
    timer.current = null;
    const { text: now, value: before, onCommit: done } = latest.current;
    if (now !== before) done(now);
  };
  useEffect(() => () => {
    if (timer.current) commit();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const Tag = multiline ? "textarea" : "input";
  return (
    <Tag
      {...rest}
      className={className}
      value={text}
      onFocus={() => { focused.current = true; }}
      onBlur={() => {
        focused.current = false;
        commit();
      }}
      onChange={(event) => {
        setText(event.target.value);
        clearTimeout(timer.current);
        timer.current = setTimeout(commit, delay);
      }}
      onKeyDown={(event) => {
        if (!multiline && event.key === "Enter") commit();
      }}
    />
  );
};

export default DraftField;
