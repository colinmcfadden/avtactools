import React from "react";
import { WRITE_IN } from "./compile";

/** One sentence of a step's script: its words, and a write-in as a line with what it is for. */
const Sentence = ({ parts, as: Tag = "p", className }) => (
  <Tag className={className}>
    {parts.map((part, j) => (part.writeIn !== undefined
      // eslint-disable-next-line react/no-array-index-key
      ? <span key={j} className="brief-writein">{part.label ? `${part.label} ` : ""}{WRITE_IN}</span>
      // eslint-disable-next-line react/no-array-index-key
      : <React.Fragment key={j}>{part.text}</React.Fragment>))}
  </Tag>
);

export default Sentence;
