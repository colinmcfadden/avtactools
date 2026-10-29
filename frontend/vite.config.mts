/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Port 3000 is load-bearing: the backend's CORS_ORIGINS defaults to
// http://localhost:3000, the dev docs and the Android dev flow assume it,
// and scripts/captureGuideScreenshots.mjs drives http://localhost:3000.
// The app stays at the root: Cesium loads from the absolute path /cesium
// (CESIUM_BASE_URL), and the demo tileset from /tiles.
export default defineConfig({
  base: "/",
  server: { port: 3000 },
  plugins: [react()],
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["src/setupTests.js"],
  },
});
