import fs from "node:fs";
import { fileURLToPath } from "node:url";
import Fastify, { type FastifyInstance } from "fastify";
import fastifyStatic from "@fastify/static";
import type { VelaConfig } from "./config.js";
import fastifyWebsocket from "@fastify/websocket";
import { API, type VelaEvent } from "@vela/shared";
import type { AppManifest } from "./manifests.js";
import type { AppRunner } from "./session/appRunner.js";

/** Fan-out of daemon events to WS /api/events clients. */
export class EventBus {
  private subs = new Set<(e: VelaEvent) => void>();
  emit = (e: VelaEvent): void => {
    this.subs.forEach((cb) => cb(e));
  };
  subscribe(cb: (e: VelaEvent) => void): () => void {
    this.subs.add(cb);
    return () => void this.subs.delete(cb);
  }
}

/** Later tasks add media, devices, etc. */
export interface Services {
  config: VelaConfig;
  manifests: AppManifest[];
  runner: AppRunner;
  bus: EventBus;
}

/** VELA_LAUNCHER_DIR overrides; default is the sibling launcher build in the monorepo. */
function launcherDir(): string {
  return process.env.VELA_LAUNCHER_DIR ?? fileURLToPath(new URL("../../launcher/dist", import.meta.url));
}

export function buildServer(s: Services): FastifyInstance {
  const app = Fastify({ logger: false });
  app.decorate("services", s);

  app.register(fastifyWebsocket);
  app.register(async (a) => {
    a.get(API.events, { websocket: true }, (socket) => {
      const off = s.bus.subscribe((e) => socket.send(JSON.stringify(e)));
      socket.on("close", off);
    });
  });

  app.get(API.health, async () => ({ ok: true }));

  app.post<{ Body: { id?: string } }>(API.launch, async (req, reply) => {
    const id = req.body?.id;
    if (typeof id !== "string") return reply.code(400).send({ error: "id required" });
    const r = await s.runner.launch(id);
    if (r.ok) return { ok: true };
    return reply.code(409).send({ reason: r.reason });
  });

  app.post(API.goHome, async () => {
    await s.runner.goHome();
    return { ok: true };
  });

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
