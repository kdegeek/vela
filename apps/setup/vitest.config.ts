import { defineConfig } from "vitest/config";
import { fileURLToPath } from "node:url";

export default defineConfig({
  test: { name: "setup", environment: "node", include: ["src/**/*.test.ts"] },
  resolve: {
    alias: {
      "@vela/shared": fileURLToPath(new URL("../../packages/shared/src/api.ts", import.meta.url)),
    },
  },
});
