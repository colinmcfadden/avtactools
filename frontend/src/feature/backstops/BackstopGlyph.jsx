/** The tool's picture: a dashed line, and an arrow back from it. */
const BackstopGlyph = () => (
  <svg width="24" height="24" viewBox="0 0 50 50" aria-hidden="true">
    <path d="M6,14 Q25,4 44,14" fill="none" stroke="#fff" strokeWidth="6" strokeDasharray="8 5" />
    <path d="M6,14 Q25,4 44,14" fill="none" stroke="#000" strokeWidth="3.5" strokeDasharray="8 5" />
    <path d="M25,10 L25,40" stroke="#FFC107" strokeWidth="4" />
    <polygon points="25,47 16,33 34,33" fill="#FFC107" stroke="#000" strokeWidth="1.5" />
  </svg>
);

export default BackstopGlyph;
