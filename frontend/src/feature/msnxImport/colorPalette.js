export const ROUTE_COLORS = [
  "#FF453A", // red
  "#0A84FF", // blue
  "#32D74B", // green
  "#FFD60A", // yellow
  "#BF5AF2", // purple
  "#FF9F0A", // orange
  "#64D2FF", // cyan
  "#FF375F", // pink
];

// Shared across imported and sketched routes so colors never collide
// between the two features.
let colorCounter = 0;

export const nextRouteColor = () => {
  const color = ROUTE_COLORS[colorCounter % ROUTE_COLORS.length];
  colorCounter += 1;
  return color;
};
