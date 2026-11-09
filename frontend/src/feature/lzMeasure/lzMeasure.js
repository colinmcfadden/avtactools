/*
 * The orange line that measures an analysed LZ: a corner, the tip of its length and the tip of its width, and
 * where along each side the other's label sits. It is kept in the diagram's `graphics.measurements`, the
 * collection the saved shape has always had (and Android and a mission pack carry as they find it), as one item
 * with a fixed id, so turning it is saved, opened again and shared like any graphic. Until it is moved there is
 * no item, and the line is laid along the LZ's box, as it always was.
 */

export const LZ_MEASURE_ID = "lz-dimensions";
export const LZ_MEASURE_TYPE = "lzDimensions";

const isPoint = (p) => Number.isFinite(p?.lat) && Number.isFinite(p?.lon);
const fraction = (t) => (Number.isFinite(t) ? Math.max(0, Math.min(1, t)) : 0.1);

/** The saved measurement among a diagram's measurements, or null when there is none it can draw. */
export const savedLzMeasure = (measurements) => {
  const item = Array.isArray(measurements)
    ? measurements.find((m) => m?.id === LZ_MEASURE_ID && m?.type === LZ_MEASURE_TYPE)
    : null;
  if (!item || !isPoint(item.corner) || !isPoint(item.lengthTip) || !isPoint(item.widthTip)) return null;
  return item;
};

/** Where the line starts on an LZ that has none saved: along the top-left corner of its box. */
export const defaultLzMeasure = (detectedLZ) => {
  if (!Array.isArray(detectedLZ) || detectedLZ.length < 3) return null;
  const lats = detectedLZ.map((p) => p[0]);
  const lons = detectedLZ.map((p) => p[1]);
  const north = Math.max(...lats);
  const south = Math.min(...lats);
  const west = Math.min(...lons);
  const east = Math.max(...lons);
  if (![north, south, west, east].every(Number.isFinite)) return null;
  return {
    corner: { lat: north, lon: west },
    lengthTip: { lat: south, lon: west },
    widthTip: { lat: north, lon: east },
    lengthLabelAt: 0.1,
    widthLabelAt: 0.1,
  };
};

/** The line as drawn: the saved one, else the default for the LZ. Label positions are fractions of 0 to 1. */
export const lzMeasureFor = (measurements, detectedLZ) => {
  const saved = savedLzMeasure(measurements);
  const line = saved ?? defaultLzMeasure(detectedLZ);
  if (!line) return null;
  return {
    corner: { lat: line.corner.lat, lon: line.corner.lon },
    lengthTip: { lat: line.lengthTip.lat, lon: line.lengthTip.lon },
    widthTip: { lat: line.widthTip.lat, lon: line.widthTip.lon },
    lengthLabelAt: fraction(line.lengthLabelAt),
    widthLabelAt: fraction(line.widthLabelAt),
  };
};

/**
 * The measurements with this line in place: the saved item changed where it stands (any field a newer version
 * added to it kept), or added at the end.
 */
export const withLzMeasure = (measurements, line) => {
  const list = Array.isArray(measurements) ? measurements : [];
  const at = list.findIndex((m) => m?.id === LZ_MEASURE_ID);
  const item = {
    ...(at >= 0 ? list[at] : {}),
    id: LZ_MEASURE_ID,
    type: LZ_MEASURE_TYPE,
    corner: { lat: line.corner.lat, lon: line.corner.lon },
    lengthTip: { lat: line.lengthTip.lat, lon: line.lengthTip.lon },
    widthTip: { lat: line.widthTip.lat, lon: line.widthTip.lon },
    lengthLabelAt: fraction(line.lengthLabelAt),
    widthLabelAt: fraction(line.widthLabelAt),
  };
  return at >= 0 ? list.map((m, i) => (i === at ? item : m)) : [...list, item];
};
