import fs from "node:fs";
import { fileURLToPath } from "node:url";
import Fastify, { type FastifyInstance } from "fastify";
import fastifyStatic from "@fastify/static";
import type { VelaConfig } from "./config.js";
import type { AppManifest } from "./manifests.js";

/** Later tasks add appRunner, media, devices, etc. */
export interface Services {
  config: VelaConfig;
  manifests: AppManifest[];
}

/** VELA_LAUNCHER_DIR overrides; default is the sibling launcher build in the monorepo. */
function launcherDir(): string {
  return process.env.VELA_LAUNCHER_DIR ?? fileURLToPath(new URL("../../launcher/dist", import.meta.url));
}

export function buildServer(s: Services): FastifyInstance {
  const app = Fastify({ logger: false });
  app.decorate("services", s);

  app.get("/api/health", async () => ({ ok: true }));

  const dir = launcherDir();
  if (fs.existsSync(dir)) {
    app.register(fastifyStatic, { root: dir });
  }
  return app;
}

declare module "fastify" {
  interface FastifyInstance {
    services: Services;
  }
}
