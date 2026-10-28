import { defineConfig } from "vitest/config";
import { fileURLToPath } from "node:url";

// Tests resolve @vela/shared to source so no build is needed before `pnpm test`.
export default defineConfig({
  test: { name: "daemon" },
  resolve: {
    alias: {
      "@vela/shared": fileURLToPath(new URL("../../packages/shared/src/api.ts", import.meta.url)),
    },
  },
});
