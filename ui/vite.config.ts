import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  // GitHub Pages serves the static demo from /<repo>/, so the asset base has to match; a local
  // or container build leaves it at the root.
  base: process.env.VITE_BASE ?? "/",
  server: {
    port: 5173,
    // the committed traces live in demo/traces/, outside ui/ — they are build inputs, not assets
    fs: { allow: [".."] },
    proxy: {
      "/api": {
        target: process.env.FORGE_API_PROXY_TARGET ?? "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
  test: {
    // vitest's default include glob otherwise picks up e2e/*.spec.ts, which are
    // Playwright specs run separately via `npm run e2e`, not vitest tests.
    exclude: ["e2e/**", "node_modules/**", "dist/**"],
    passWithNoTests: true,
  },
});
