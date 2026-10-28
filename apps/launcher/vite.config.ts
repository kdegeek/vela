import { defineConfig } from "vite";
import { svelte } from "@sveltejs/vite-plugin-svelte";
import { fileURLToPath } from "node:url";

// Built into dist/, which the daemon serves at http://127.0.0.1:7300/.
export default defineConfig({
  plugins: [svelte()],
  resolve: {
    alias: {
      "@vela/shared": fileURLToPath(new URL("../../packages/shared/src/api.ts", import.meta.url)),
    },
  },
  build: { outDir: "dist", emptyOutDir: true, target: "chrome120", assetsInlineLimit: 0 },
  server: { proxy: { "/api": { target: "http://127.0.0.1:7300", ws: true } } },
});
