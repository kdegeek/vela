import { defineConfig } from "vitest/config";
import { fileURLToPath } from "node:url";

// Pure libs only; the Svelte plugin stays out of tests. @vela/shared resolves to source.
export default defineConfig({
  test: { name: "launcher", environment: "node", include: ["src/**/*.test.ts"] },
  resolve: {
    alias: {
      "@vela/shared": fileURLToPath(new URL("../../packages/shared/src/api.ts", import.meta.url)),
    },
  },
});
