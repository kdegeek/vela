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
    for (const cb of this.subs) {
      try {
        cb(e);
      } catch (err) {
        console.error("vela: event subscriber failed:", (err as Error).message);
      }
    }
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

export const PORT = 7300;

export function buildServer(s: Services): FastifyInstance {
  const app = Fastify({ logger: false });
  app.decorate("services", s);

  // Guard against web pages on this box (CSRF, cross-site WebSocket, DNS rebinding).
  const listen = s.config.server.listen;
  const hosts = new Set([`127.0.0.1:${PORT}`, `localhost:${PORT}`, `${listen}:${PORT}`]);
  const origins = new Set([`http://127.0.0.1:${PORT}`, `http://localhost:${PORT}`]);
  app.addHook("onRequest", async (req, reply) => {
    if (!req.url.startsWith("/api/")) return;
    if (listen !== "0.0.0.0" && !hosts.has(req.headers.host ?? "")) return reply.code(403).send({ error: "bad host" });
    const origin = req.headers.origin;
    if (origin !== undefined && !origins.has(origin)) return reply.code(403).send({ error: "bad origin" });
    if (req.method === "POST" && !(req.headers["content-type"] ?? "").toLowerCase().startsWith("application/json")) {
      return reply.code(415).send({ error: "content-type must be application/json" });
    }
  });

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
