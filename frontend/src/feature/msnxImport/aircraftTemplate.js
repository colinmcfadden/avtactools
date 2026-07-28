import JSZip from "jszip";
import api from "../auth/api";

/**
 * Choosing the AMPS package an export is built on.
 *
 * The bundled template carries a UH-60L vehicle installation (a native VPM
 * model only AMPS can produce). An airframe therefore can't be synthesised —
 * to export a mission AMPS opens as an Apache or a Chinook, real bytes have to
 * come from AMPS. An admin attaches those to a profile in two possible shapes:
 *
 *   msnx — a whole mission saved out of AMPS. Used as the base package
 *          directly: vidx, FileInfo, .rels, and vehicles.xml already agree
 *          because AMPS wrote them together.
 *   vidx — a bare vehicle installation, transplanted into the default template
 *          by rewriting the OPC part names and the vehicledescription.
 *
 * With neither, export falls back to the default template and reports it, so
 * the user learns their file will open as a UH-60L rather than discovering it
 * in AMPS.
 */

const DEFAULT_TEMPLATE_URL = "/msnx_template.msnx";

// OPC part names percent-encode spaces.
const VEHICLE_DIR = "Vehicle%20Installations";
const FOLDER_RELS = `_rels/${VEHICLE_DIR}.folder.rels`;
const VEHICLES_XML = "mission/vehicles.xml";

const vidxPart = (name) => `${VEHICLE_DIR}/${name}.vidx`;
const vidxInfoPart = (name) => `${VEHICLE_DIR}/${name}.vidx.FileInfo`;
const vidxRelsPart = (name) => `${VEHICLE_DIR}/_rels/${name}.vidx.rels`;

/** The airframe name of the .vidx currently inside a template package. */
const findInstalledVidx = (zip) => {
  const pattern = new RegExp(`^${VEHICLE_DIR}/([^/]+)\\.vidx$`);
  for (const path of Object.keys(zip.files)) {
    const match = pattern.exec(path);
    if (match) return match[1];
  }
  return null;
};

/** The airframe name a standalone .vidx declares, from its root manifest xml. */
const readVidxAirframe = (vidxZip) => {
  for (const path of Object.keys(vidxZip.files)) {
    if (/^[^/]+\.xml$/i.test(path)) return path.replace(/\.xml$/i, "");
  }
  return null;
};

/**
 * Replaces the template's vehicle installation with `vidxBytes`.
 *
 * Rewrites every OPC part that names the old airframe: the .vidx itself, its
 * FileInfo and .rels, and the folder relationship that points at it. The
 * FileInfo is carried over byte-for-byte under the new name — it is a .NET
 * metadata blob recording where the file originally came from on disk, which
 * is inert for planning, and re-encoding its length-prefixed strings would be
 * a good deal riskier than leaving a stale path in it.
 */
const transplantVidx = async (zip, vidxBytes, vehicleDescription) => {
  const installed = findInstalledVidx(zip);
  if (!installed) {
    throw new Error("The mission template has no vehicle installation to replace.");
  }

  let airframe;
  try {
    airframe = readVidxAirframe(await JSZip.loadAsync(vidxBytes));
  } catch {
    throw new Error("That aircraft's vehicle installation couldn't be read.");
  }
  if (!airframe) {
    throw new Error("That vehicle installation has no manifest, so it can't be installed.");
  }

  const fileInfo = zip.file(vidxInfoPart(installed));
  const fileInfoBytes = fileInfo ? await fileInfo.async("uint8array") : null;

  zip.remove(vidxPart(installed));
  zip.remove(vidxInfoPart(installed));
  zip.remove(vidxRelsPart(installed));

  zip.file(vidxPart(airframe), vidxBytes);
  if (fileInfoBytes) {
    zip.file(vidxInfoPart(airframe), fileInfoBytes);
    zip.file(
      vidxRelsPart(airframe),
      '<?xml version="1.0" encoding="utf-8"?>'
        + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        + `<Relationship Type="http://www.jtesolutions.com/packaging/fileinfo" Target="/${vidxInfoPart(airframe)}" Id="fileinfo" />`
        + "</Relationships>",
    );
  }

  const relsFile = zip.file(FOLDER_RELS);
  if (relsFile) {
    const rels = await relsFile.async("string");
    zip.file(FOLDER_RELS, rels.split(vidxPart(installed)).join(vidxPart(airframe)));
  }

  // Keep vehicles.xml consistent with the installation now in the package.
  if (vehicleDescription) {
    const vehiclesFile = zip.file(VEHICLES_XML);
    if (vehiclesFile) {
      const xml = await vehiclesFile.async("string");
      zip.file(
        VEHICLES_XML,
        xml.replace(
          /<vehicledescription>[\s\S]*?<\/vehicledescription>/i,
          `<vehicledescription>${vehicleDescription}</vehicledescription>`,
        ),
      );
    }
  }

  return airframe;
};

const fetchDefaultTemplate = async () => {
  const res = await fetch(DEFAULT_TEMPLATE_URL);
  if (!res.ok) {
    throw new Error("Couldn't load the mission template (msnx_template.msnx).");
  }
  return res.arrayBuffer();
};

/**
 * Template bytes for an export, plus what the user should be told about them.
 *
 * @returns {Promise<{ data: ArrayBuffer|Uint8Array, airframe: string|null,
 *                     exact: boolean, warning: string|null }>}
 *   `exact` is true only when the package genuinely carries the selected
 *   airframe. When it's false, `warning` says what AMPS will actually open.
 */
export const resolveExportTemplate = async (profile) => {
  const fallback = async (warning) => ({
    data: await fetchDefaultTemplate(),
    airframe: "UH-60L",
    exact: false,
    warning,
  });

  const designation = profile?.designation || "the selected aircraft";
  const isUh60l = /^uh-?60l$/i.test(profile?.designation || "");

  // The bundled template *is* a UH-60L, so that selection is already exact.
  if (!profile || isUh60l) {
    return { data: await fetchDefaultTemplate(), airframe: "UH-60L", exact: true, warning: null };
  }

  if (!profile.has_template || !profile.id) {
    return fallback(
      `This mission will open in AMPS as a UH-60L, not a ${designation}. `
        + `Planned speeds, altitudes, and winds still export correctly. To get a true `
        + `${designation} file, an administrator needs to attach that airframe's AMPS `
        + `package to the profile.`,
    );
  }

  let bytes;
  try {
    const res = await api.get(`/aircraft-profiles/${profile.id}/template`, {
      responseType: "arraybuffer",
    });
    bytes = res.data;
  } catch {
    return fallback(
      `Couldn't load the ${designation} package, so this mission was built on the `
        + `standard UH-60L template. Planned values are unaffected.`,
    );
  }

  if (profile.template_kind === "msnx") {
    // AMPS wrote the whole package, so it is internally consistent already.
    return { data: bytes, airframe: designation, exact: true, warning: null };
  }

  try {
    const zip = await JSZip.loadAsync(await fetchDefaultTemplate());
    const airframe = await transplantVidx(
      zip,
      bytes,
      profile.amps_vehicle_description,
    );
    const data = await zip.generateAsync({ type: "arraybuffer" });
    return { data, airframe: airframe || designation, exact: true, warning: null };
  } catch (err) {
    return fallback(
      `The ${designation} vehicle installation couldn't be applied (${err.message}), `
        + `so this mission was built on the standard UH-60L template.`,
    );
  }
};
