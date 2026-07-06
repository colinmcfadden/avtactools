/**
 * Drives the running app (backend :5000, frontend :3000) with Playwright and
 * captures the screenshots referenced by docs/USER_GUIDE.md.
 *
 * Run from frontend/:  node scripts/captureGuideScreenshots.mjs
 */
import { chromium } from "playwright";
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const OUT_DIR = path.resolve(__dirname, "../../docs/images");
fs.mkdirSync(OUT_DIR, { recursive: true });

const APP_URL = "http://localhost:3000";
// MGRS near the demo route bundled in msnx_template.msnx
const DEMO_GRID = "16S GD 63085 39644";

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const run = async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1600, height: 900 } });
  const page = await context.newPage();

  // Queue of values for window.prompt; alerts/confirms just get accepted.
  const promptQueue = [];
  page.on("dialog", async (dialog) => {
    if (dialog.type() === "prompt") {
      await dialog.accept(promptQueue.shift() ?? dialog.defaultValue());
    } else {
      await dialog.accept();
    }
  });

  const shot = async (name) => {
    await page.screenshot({ path: path.join(OUT_DIR, name) });
    console.log("captured", name);
  };

  const mapClick = async (x, y, options = {}) => {
    const map = page.locator("#map-to-export");
    await map.click({ position: { x, y }, ...options });
  };

  console.log("loading app...");
  await page.goto(APP_URL);
  await page.waitForSelector(".leaflet-container");
  await sleep(4000); // initial tiles
  await shot("01-overview.png");

  // --- MGRS search ---
  console.log("search...");
  await page.fill(".ff-input", DEMO_GRID);
  await page.click("button:has-text('GO')");
  await sleep(5000); // zoom + tiles + doghouses
  await shot("02-search.png");

  // --- placement tools ---
  console.log("placement tools...");
  await page.click("button[title='Add Helo']");
  await sleep(400);
  await page.click("button[title='Add Helo']");
  await sleep(400);
  await page.click("button[title='PZ/Pickup']");
  await sleep(400);
  await page.click("button[title='Sector']");
  await sleep(400);
  await page.click("button:has-text('Unit')");
  await sleep(300);
  await page.click(".dropdown-item >> nth=0");
  await sleep(400);
  await page.click("button:has-text('L-GA')");
  await sleep(800);
  await shot("03-tools.png");

  // --- terrain analysis ---
  console.log("terrain analysis (SAM inference, may take a while)...");
  await page.click("button:has-text('Analyze the LZ')");
  await page.waitForSelector(".loading-overlay", { timeout: 10000 }).catch(() => {});
  await page.waitForSelector(".loading-overlay", { state: "detached", timeout: 180000 });
  await sleep(1500);
  await shot("04-analysis.png");

  // slope heatmap (the real checkbox is visually hidden; click the switch)
  const slopeSlider = page.locator(".toggle-item", { hasText: "Slope Map" }).locator(".slider");
  await slopeSlider.click();
  await sleep(1200);
  await shot("05-slopemap.png");
  await slopeSlider.click();
  await sleep(500);

  // --- sketch a route ---
  console.log("sketch route...");
  promptQueue.push("ALPHA");
  await page.click("button[title='Sketch a route (click the map to add points)']");
  await sleep(300);
  const sketchPoints = [
    [500, 700], [620, 560], [760, 620], [900, 480], [1040, 520], [1180, 380],
  ];
  for (const [x, y] of sketchPoints) {
    await mapClick(x, y);
    await sleep(250);
  }
  await sleep(300);
  await shot("06-sketch-draft.png");
  await page.click("button:has-text('End Route')");
  await sleep(800);
  await shot("07-sketch-done.png");

  // --- designate a point ---
  console.log("designate point...");
  await mapClick(900, 480, { button: "right" });
  await sleep(400);
  await shot("08-designate-menu.png");
  promptQueue.push(".LZ DEMO");
  await page.click("button:has-text('LZ / PZ (Target)')");
  await sleep(600);
  await shot("09-designated.png");

  // --- import a msnx ---
  console.log("import msnx...");
  const templatePath = path.resolve(__dirname, "../public/msnx_template.msnx");
  await page.setInputFiles("input[type='file'][accept='.msnx']", templatePath);
  await sleep(1200);
  // zoom out a bit so the imported route is visible
  await mapClick(800, 450);
  for (let i = 0; i < 3; i++) {
    await page.click(".leaflet-control-zoom-out");
    await sleep(700);
  }
  await sleep(2500);
  await shot("10-import.png");

  // --- routes panel detail ---
  await page
    .locator(".floating-routes-panel")
    .screenshot({ path: path.join(OUT_DIR, "11-routes-panel.png") });
  console.log("captured 11-routes-panel.png");

  // --- export modal (capture area + modal) ---
  console.log("export flow...");
  await page.click("button:has-text('Set Capture Area')");
  await sleep(800);
  await shot("12-capture-area.png");
  await page.click("button:has-text('Export LZ Card')");
  await page.waitForSelector(".export-modal-container", { timeout: 15000 });
  await sleep(800);
  await shot("13-export-modal.png");
  await page.click(".export-modal-container .close-btn");
  await sleep(400);

  // --- auth cluster detail ---
  await page
    .locator(".floating-topright")
    .screenshot({ path: path.join(OUT_DIR, "14-auth-cluster.png") });
  console.log("captured 14-auth-cluster.png");

  // --- falcon easter egg ---
  console.log("falcon...");
  await page.click(".unit-badge");
  await sleep(1100);
  await shot("15-falcon.png");
  await sleep(2000);

  await browser.close();
  console.log("done ->", OUT_DIR);
};

run().catch((err) => {
  console.error(err);
  process.exit(1);
});
