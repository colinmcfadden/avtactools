/**
 * Copy pdf.js's worker and data files into public/pdfjs/ before dev or build (docs/DOCUMENTS.md §8.1).
 *
 * The document viewer loads pdf.js itself through a dynamic import, but its worker, and the files it reads while it draws a page
 * (character maps, the standard fonts a PDF names without embedding them, the WebAssembly decoders for JPEG 2000 and colour profiles),
 * are fetched at run time from URLs it is given. Copied here they are served from the site's own origin, at the one version in
 * package.json, and kept out of source control as Cesium's are.
 */
const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..", "node_modules", "pdfjs-dist");
const destination = path.join(__dirname, "..", "public", "pdfjs");

if (!fs.existsSync(root)) {
  console.error("pdf.js not found — run `npm install` first.");
  process.exit(1);
}

const stamp = path.join(destination, ".copied-from");
// Which build too: a copy from before the legacy worker was taken must be made again.
const version = `${require("pdfjs-dist/package.json").version} legacy`;
if (fs.existsSync(stamp) && fs.readFileSync(stamp, "utf8") === version) {
  process.exit(0);
}

fs.rmSync(destination, { recursive: true, force: true });
fs.mkdirSync(destination, { recursive: true });
// The legacy build's worker, as pdfLoader.js loads the legacy build: the modern one needs APIs older browsers lack.
fs.copyFileSync(path.join(root, "legacy", "build", "pdf.worker.min.mjs"), path.join(destination, "pdf.worker.min.mjs"));
for (const folder of ["cmaps", "standard_fonts", "wasm", "iccs"]) {
  fs.cpSync(path.join(root, folder), path.join(destination, folder), { recursive: true });
}
fs.copyFileSync(path.join(root, "LICENSE"), path.join(destination, "LICENSE"));
fs.writeFileSync(stamp, version);
console.log(`Copied pdf.js ${version} files to public/pdfjs`);
