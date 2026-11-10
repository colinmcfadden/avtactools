import { BACKSTOP_RED } from "./backstopStyle";

const LINE = "M6,14 Q25,4 44,14";
const DASHES = { strokeDasharray: "4 11", strokeLinecap: "square" };

/** The tool's picture: a dashed line, and an arrow back from it, in red outlined in white as they are on the map. */
const BackstopGlyph = () => (
  <svg width="24" height="24" viewBox="0 0 50 50" aria-hidden="true">
    <path d={LINE} fill="none" stroke="#fff" strokeWidth="7" {...DASHES} />
    <path d={LINE} fill="none" stroke={BACKSTOP_RED} strokeWidth="4" {...DASHES} />
    <path d="M25,10 L25,36" stroke="#fff" strokeWidth="7" />
    <path d="M25,10 L25,36" stroke={BACKSTOP_RED} strokeWidth="4" />
    <polygon points="25,47 16,33 34,33" fill={BACKSTOP_RED} stroke="#fff" strokeWidth="2" paintOrder="stroke" />
  </svg>
);

export default BackstopGlyph;
