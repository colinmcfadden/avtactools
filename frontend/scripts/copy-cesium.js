/**
 * Copy Cesium's static assets into public/ before dev or build.
 *
 * Cesium loads its web workers, shaders and widget assets at runtime from
 * CESIUM_BASE_URL rather than through the bundler, so they have to exist as
 * plain files. CRA can't be configured to emit them without ejecting, and they
 * are ~40 MB of third-party build output, so they are copied on demand and kept
 * out of source control.
 */
const fs = require("fs");
const path = require("path");

const source = path.join(__dirname, "..", "node_modules", "cesium", "Build", "Cesium");
const destination = path.join(__dirname, "..", "public", "cesium");

if (!fs.existsSync(source)) {
  console.error("Cesium build assets not found — run `npm install` first.");
  process.exit(1);
}

// Skip the copy when it is already current: this runs before every start and
// build, and the tree is large enough that repeating it is a noticeable wait.
const stamp = path.join(destination, ".copied-from");
const version = require("cesium/package.json").version;
if (fs.existsSync(stamp) && fs.readFileSync(stamp, "utf8") === version) {
  process.exit(0);
}

fs.rmSync(destination, { recursive: true, force: true });
fs.cpSync(source, destination, { recursive: true });
fs.writeFileSync(stamp, version);
console.log(`Copied Cesium ${version} assets to public/cesium`);
