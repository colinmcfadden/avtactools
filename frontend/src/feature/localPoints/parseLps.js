import { SqliteReader } from "./sqliteReader.js";

/**
 * .LPS local point sets (AMPS "Local Points") are SpatiaLite databases with a
 * Points table whose Coordinate column holds a SpatiaLite POINT geometry blob:
 *   [0]=0x00  [1]=endianness  [2..5]=SRID  [6..37]=MBR (4 doubles)
 *   [38]=0x7C  [39..42]=class (1 = POINT)  [43..50]=x  [51..58]=y  [59]=0xFE
 */
const decodeSpatialitePoint = (blob) => {
  if (!blob || blob.length < 59 || blob[0] !== 0x00) return null;
  const littleEndian = blob[1] === 0x01;
  const view = new DataView(blob.buffer, blob.byteOffset, blob.byteLength);
  const classType = view.getUint32(39, littleEndian);
  if (classType !== 1) return null; // only POINT geometries appear in .LPS files
  const x = view.getFloat64(43, littleEndian);
  const y = view.getFloat64(51, littleEndian);
  return { lon: x, lat: y };
};

/**
 * Parses an .LPS file into a point set:
 * { name, points: [{ id, name, description, group, icon, elevationFt, lat, lon }] }
 */
export async function parseLpsFile(file) {
  const buffer = await file.arrayBuffer();

  let reader;
  try {
    reader = new SqliteReader(buffer);
  } catch {
    throw new Error("This doesn't look like an .LPS local points file.");
  }

  const rows = reader.readTable("Points");
  if (!rows) {
    throw new Error("No Points table found — is this an .LPS local points file?");
  }

  const points = [];
  for (const row of rows) {
    const coord = decodeSpatialitePoint(row.Coordinate);
    if (!coord) continue;
    points.push({
      id: `lps-${row.Pedigree ?? points.length}-${Math.random().toString(36).slice(2, 8)}`,
      name: (row.ID || "").trim(),
      description: (row.Description || "").trim(),
      group: (row.GroupName || "Default").trim(),
      icon: (row.IconName || "").trim(),
      // .LPS Elevation values are feet (USER-entered LZ elevations).
      elevationFt: typeof row.Elevation === "number" ? row.Elevation : null,
      lat: coord.lat,
      lon: coord.lon,
    });
  }

  if (points.length === 0) {
    throw new Error("This .LPS file contains no readable points.");
  }

  return {
    name: file.name.replace(/\.lps$/i, ""),
    points,
  };
}
