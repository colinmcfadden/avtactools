/**
 * The phase line tool's picture: the line with PL over it, in the colour of the button's words. The letters are drawn, not
 * written, so the picture adds no words of its own to the button it is on.
 */
const PhaseLineGlyph = ({ size = 24, color = "currentColor" }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M6.5,12 V4 H9.5 A2.25,2.25 0 0 1 9.5,8.5 H6.5 M14,4 V12 H18" strokeWidth="1.9" />
    <path d="M2,18.5 H22" strokeWidth="2.5" />
  </svg>
);

export default PhaseLineGlyph;
