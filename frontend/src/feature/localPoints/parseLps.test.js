import fs from "fs";
import path from "path";
import { TextDecoder as NodeTextDecoder, TextEncoder as NodeTextEncoder } from "util";
import { parseLpsFile } from "./parseLps";

// jsdom doesn't provide TextDecoder/TextEncoder; the browser does.
beforeAll(() => {
  if (typeof global.TextDecoder === "undefined") {
    global.TextDecoder = NodeTextDecoder;
  }
  if (typeof global.TextEncoder === "undefined") {
    global.TextEncoder = NodeTextEncoder;
  }
});

const LPS_PATH = path.join(__dirname, "../../../public/NORTH GEORGIA POINTS.LPS");

const asFakeFile = (filePath) => {
  const bytes = fs.readFileSync(filePath);
  return {
    name: path.basename(filePath),
    arrayBuffer: async () =>
      bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength),
  };
};

test("parses the North Georgia sample .LPS", async () => {
  const set = await parseLpsFile(asFakeFile(LPS_PATH));

  expect(set.name).toBe("NORTH GEORGIA POINTS");
  expect(set.points).toHaveLength(49);

  const p = set.points.find((pt) => pt.name === "3MILE");
  expect(p).toBeDefined();
  expect(p.lat).toBeCloseTo(33.948, 2);
  expect(p.lon).toBeCloseTo(-84.481, 2);
  expect(p.elevationFt).toBe(921);
  expect(p.group).toBe("Default");

  // Every point lands in the North Georgia region.
  for (const pt of set.points) {
    expect(pt.lat).toBeGreaterThan(30);
    expect(pt.lat).toBeLessThan(38);
    expect(pt.lon).toBeGreaterThan(-88);
    expect(pt.lon).toBeLessThan(-80);
  }
});

test("rejects non-SQLite files", async () => {
  const fake = {
    name: "junk.lps",
    arrayBuffer: async () => new TextEncoder().encode("not a database").buffer,
  };
  await expect(parseLpsFile(fake)).rejects.toThrow(/doesn't look like an \.LPS/);
});
