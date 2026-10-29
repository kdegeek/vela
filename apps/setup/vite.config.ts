import { defineConfig } from "vite";
import { fileURLToPath } from "node:url";

// Built into dist/, which the daemon's setup listener serves on :7301 (VELA_SETUP_DIR, /usr/lib/vela/setup in the
// image). The listener's CSP allows only same-origin scripts, so nothing may be inlined into index.html.
export default defineConfig({
  resolve: {
    alias: {
      "@vela/shared": fileURLToPath(new URL("../../packages/shared/src/api.ts", import.meta.url)),
    },
  },
  build: { outDir: "dist", emptyOutDir: true, target: ["safari16", "chrome120"], assetsInlineLimit: 0, modulePreload: { polyfill: false } },
  server: { proxy: { "/setup/api": { target: "http://127.0.0.1:7301" } } },
});
