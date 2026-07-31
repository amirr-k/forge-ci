import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
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
