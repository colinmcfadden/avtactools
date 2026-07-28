import fs from "fs";
import path from "path";
import JSZip from "jszip";
import { readMissionAircraft } from "./parseMsnx";

/**
 * The vidx transplant rewrites OPC part names in a real AMPS package, so these
 * exercise the actual template bytes rather than a fixture. The transplant
 * itself is re-implemented against JSZip here because aircraftTemplate.js pulls
 * in the axios api client, which needs a browser environment.
 */

const TEMPLATE = path.join(__dirname, "../../../public/msnx_template.msnx");
const VEHICLE_DIR = "Vehicle%20Installations";
const VEHICLES_XML = "mission/vehicles.xml";
const FOLDER_RELS = `_rels/${VEHICLE_DIR}.folder.rels`;

const loadTemplate = () => JSZip.loadAsync(fs.readFileSync(TEMPLATE));

const findInstalledVidx = (zip) => {
  const pattern = new RegExp(`^${VEHICLE_DIR}/([^/]+)\\.vidx$`);
  for (const p of Object.keys(zip.files)) {
    const m = pattern.exec(p);
    if (m) return m[1];
  }
  return null;
};

describe("the bundled template", () => {
  it("carries exactly one UH-60L vehicle installation", async () => {
    const zip = await loadTemplate();
    expect(findInstalledVidx(zip)).toBe("UH60L");
    expect(zip.file(`${VEHICLE_DIR}/UH60L.vidx.FileInfo`)).toBeTruthy();
    expect(zip.file(`${VEHICLE_DIR}/_rels/UH60L.vidx.rels`)).toBeTruthy();
  });

  it("declares the airframe in vehicles.xml where import reads it", async () => {
    const zip = await loadTemplate();
    const xml = await zip.file(VEHICLES_XML).async("string");
    const aircraft = readMissionAircraft(xml);
    expect(aircraft.description).toBe("Air:Rotary Wing:H60:9856:Default:1.0014:UH-60L");
    expect(aircraft.designation).toBe("UH-60L");
  });

  it("has a .vidx that is itself a zip with a root manifest", async () => {
    const zip = await loadTemplate();
    const bytes = await zip.file(`${VEHICLE_DIR}/UH60L.vidx`).async("uint8array");
    const inner = await JSZip.loadAsync(bytes);
    const roots = Object.keys(inner.files).filter((p) => /^[^/]+\.xml$/i.test(p));
    expect(roots).toContain("UH60L.xml");
  });
});

describe("readMissionAircraft", () => {
  it("returns null when a package has no vehicle data", () => {
    expect(readMissionAircraft("")).toBeNull();
    expect(readMissionAircraft("<vehicles></vehicles>")).toBeNull();
  });

  it("takes the designation from the last field", () => {
    const parsed = readMissionAircraft(
      "<vehicles><vehicle><vehicledescription>Air:Rotary Wing:H47:1:Default:1.0:CH-47F</vehicledescription></vehicle></vehicles>",
    );
    expect(parsed.designation).toBe("CH-47F");
  });
});

describe("vidx transplant", () => {
  // Mirrors transplantVidx in aircraftTemplate.js.
  const transplant = async (zip, vidxBytes, airframe, description) => {
    const installed = findInstalledVidx(zip);
    const info = await zip.file(`${VEHICLE_DIR}/${installed}.vidx.FileInfo`).async("uint8array");

    zip.remove(`${VEHICLE_DIR}/${installed}.vidx`);
    zip.remove(`${VEHICLE_DIR}/${installed}.vidx.FileInfo`);
    zip.remove(`${VEHICLE_DIR}/_rels/${installed}.vidx.rels`);

    zip.file(`${VEHICLE_DIR}/${airframe}.vidx`, vidxBytes);
    zip.file(`${VEHICLE_DIR}/${airframe}.vidx.FileInfo`, info);
    zip.file(
      `${VEHICLE_DIR}/_rels/${airframe}.vidx.rels`,
      `<?xml version="1.0" encoding="utf-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Type="http://www.jtesolutions.com/packaging/fileinfo" Target="/${VEHICLE_DIR}/${airframe}.vidx.FileInfo" Id="fileinfo" /></Relationships>`,
    );

    const rels = await zip.file(FOLDER_RELS).async("string");
    zip.file(
      FOLDER_RELS,
      rels
        .split(`${VEHICLE_DIR}/${installed}.vidx`)
        .join(`${VEHICLE_DIR}/${airframe}.vidx`),
    );

    const xml = await zip.file(VEHICLES_XML).async("string");
    zip.file(
      VEHICLES_XML,
      xml.replace(
        /<vehicledescription>[\s\S]*?<\/vehicledescription>/i,
        `<vehicledescription>${description}</vehicledescription>`,
      ),
    );
    return zip;
  };

  it("leaves a package with no dangling references to the old airframe", async () => {
    const zip = await loadTemplate();
    const original = await zip.file(`${VEHICLE_DIR}/UH60L.vidx`).async("uint8array");
    const description = "Air:Rotary Wing:H47:1234:Default:1.0:CH-47F";

    await transplant(zip, original, "CH47F", description);

    // Old parts gone, new parts present.
    expect(zip.file(`${VEHICLE_DIR}/UH60L.vidx`)).toBeNull();
    expect(zip.file(`${VEHICLE_DIR}/UH60L.vidx.FileInfo`)).toBeNull();
    expect(zip.file(`${VEHICLE_DIR}/_rels/UH60L.vidx.rels`)).toBeNull();
    expect(findInstalledVidx(zip)).toBe("CH47F");

    // The folder relationship must point at the new part or AMPS can't find it.
    const rels = await zip.file(FOLDER_RELS).async("string");
    expect(rels).toContain(`/${VEHICLE_DIR}/CH47F.vidx`);
    expect(rels).not.toContain("UH60L.vidx");

    // vehicles.xml must agree with the installation now in the package.
    const aircraft = readMissionAircraft(await zip.file(VEHICLES_XML).async("string"));
    expect(aircraft.description).toBe(description);
    expect(aircraft.designation).toBe("CH-47F");
  });

  it("survives a real zip round-trip", async () => {
    const zip = await loadTemplate();
    const original = await zip.file(`${VEHICLE_DIR}/UH60L.vidx`).async("uint8array");
    await transplant(zip, original, "AH64E", "Air:Rotary Wing:H64:1:Default:1.0:AH-64E");

    const rebuilt = await JSZip.loadAsync(await zip.generateAsync({ type: "nodebuffer" }));
    expect(findInstalledVidx(rebuilt)).toBe("AH64E");
    // The mission payload the exporter writes must still be intact.
    for (const part of ["mission.gpx", "mission/points.xml", "mission/legs.xml"]) {
      expect(rebuilt.file(part)).toBeTruthy();
    }
    const moved = await rebuilt.file(`${VEHICLE_DIR}/AH64E.vidx`).async("uint8array");
    expect(moved.length).toBe(original.length);
  });
});
