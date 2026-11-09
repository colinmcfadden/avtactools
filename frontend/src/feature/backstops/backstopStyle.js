export const BACKSTOP_RED = "#e60000";

// Red dashes outlined in white. Both are square-capped on one dash pattern, so the white goes round each dash's ends as well as its sides
// (the map draws on a canvas, which has no dash offset to shift the white along by). A cap adds half the line's width to each end of a dash.
const DASHES = "7 10";
export const BACKSTOP_CASING = { color: "#ffffff", weight: 5, dashArray: DASHES, lineCap: "square" };
export const BACKSTOP_LINE = { color: BACKSTOP_RED, weight: 3, dashArray: DASHES, lineCap: "square" };
