/** The FLOT tool's picture: a run of its scallops, in its blue unless the button gives it another colour. */
const FlotGlyph = ({ size = 24, color = "#2f8fff" }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
    <path d="M2,16 A3.33,3.33 0 0 1 8.67,16 A3.33,3.33 0 0 1 15.33,16 A3.33,3.33 0 0 1 22,16" fill="none" stroke={color} strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
  </svg>
);

export default FlotGlyph;
