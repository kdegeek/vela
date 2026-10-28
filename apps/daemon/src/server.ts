import fs from "node:fs";
import path from "node:path";
import { Readable } from "node:stream";
import { fileURLToPath } from "node:url";
import Fastify, { type FastifyInstance } from "fastify";
import fastifyStatic from "@fastify/static";
import type { VelaConfig } from "./config.js";
import fastifyWebsocket from "@fastify/websocket";
import { API, isMediaKey, type HomeState, type VelaEvent } from "@vela/shared";
import { ART_WIDTHS, type JellyfinClient } from "./media/jellyfin.js";
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
  /** Builds the home screen state. */
  home?: () => Promise<HomeState>;
  /** Present when the media provider is jellyfin; backs the art proxy. */
  jellyfin?: JellyfinClient;
  /** Starts Jellyfin playback of an item; resolves once mpv is launched, not when it ends. */
  play?: (itemId: string) => Promise<void>;
}

/** VELA_ASSETS_DIR overrides; default is apps/daemon/assets for both src/ and dist/. */
function assetsDir(): string {
  return process.env.VELA_ASSETS_DIR ?? fileURLToPath(new URL("../assets", import.meta.url));
}

const ALLOWED_PROXY_TYPES = new Set(["image/jpeg", "image/png", "image/webp"]);

/** Headers that stop a served file from being treated as a page, even if its type were wrong. */
function hardened(reply: import("fastify").FastifyReply) {
  return reply.header("x-content-type-options", "nosniff").header("content-security-policy", "default-src 'none'; sandbox");
}

const SAFE_FILE = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;
const MIME: Record<string, string> = { ".svg": "image/svg+xml", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png" };

/** Serves one file from a flat directory; a name with any path component gets 404. */
async function sendAsset(dir: string, file: string, reply: import("fastify").FastifyReply) {
  const mime = MIME[path.extname(file).toLowerCase()];
  const full = path.join(dir, file);
  if (!SAFE_FILE.test(file) || !mime || !fs.existsSync(full)) return reply.code(404).send({ error: "not found" });
  return hardened(reply).header("content-type", mime).header("cache-control", "max-age=86400").send(fs.readFileSync(full));
}

/** VELA_LAUNCHER_DIR overrides; default is the sibling launcher build in the monorepo. */
function launcherDir(): string {
  return process.env.VELA_LAUNCHER_DIR ?? fileURLToPath(new URL("../../launcher/dist", import.meta.url));
}

export const PORT = 7300;

export function buildServer(s: Services): FastifyInstance {
  const app = Fastify({ logger: false });
  app.decorate("services", s);

  // Applies to every request regardless of path (the router normalizes paths). Guard against web pages on this box (CSRF, cross-site WebSocket, DNS rebinding).
  const listen = s.config.server.listen;
  const hosts = new Set([`127.0.0.1:${PORT}`, `localhost:${PORT}`, `${listen}:${PORT}`]);
  const origins = new Set([`http://127.0.0.1:${PORT}`, `http://localhost:${PORT}`]);
  app.addHook("onRequest", async (req, reply) => {
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

  app.post<{ Body: { key?: string } }>(API.play, async (req, reply) => {
    const key = req.body?.key;
    if (typeof key !== "string" || !isMediaKey(key)) return reply.code(400).send({ error: "key required" });
    if (key.startsWith("mock:")) {
      s.bus.emit({ type: "toast", message: "Mock titles can't be played. Connect Jellyfin in /etc/vela/vela.toml." });
      return reply.code(409).send({ reason: "mock" });
    }
    if (!s.play) return reply.code(503).send({ error: "jellyfin is not configured" });
    await s.play(key.slice("jellyfin:".length));
    return { ok: true };
  });

  app.post(API.goHome, async () => {
    await s.runner.goHome();
    return { ok: true };
  });

  app.get(API.home, async (_req, reply) => {
    if (!s.home) return reply.code(503).send({ error: "home state unavailable" });
    return s.home();
  });

  app.get<{ Params: { file: string } }>("/api/glyphs/:file", (req, reply) => sendAsset(path.join(assetsDir(), "glyphs"), req.params.file, reply));
  app.get<{ Params: { file: string } }>("/api/art/mock/:file", (req, reply) => sendAsset(path.join(assetsDir(), "mock"), req.params.file, reply));

  app.get<{ Params: { id: string; type: string }; Querystring: { w?: string } }>("/api/art/jellyfin/:id/:type", async (req, reply) => {
    const { id, type } = req.params;
    const w = Number(req.query.w);
    if (!(ART_WIDTHS as readonly number[]).includes(w)) return reply.code(400).send({ error: "w must be 3840, 1280 or 704" });
    if (type !== "Backdrop" && type !== "Primary") return reply.code(400).send({ error: "type must be Backdrop or Primary" });
    if (!/^[0-9a-fA-F-]{8,40}$/.test(id)) return reply.code(400).send({ error: "bad id" });
    if (!s.jellyfin) return reply.code(404).send({ error: "jellyfin is not configured" });
    let up: Response;
    try {
      up = await s.jellyfin.fetchImage(id, type, w);
    } catch {
      return reply.code(502).send({ error: "Jellyfin isn't reachable" });
    }
    if (!up.ok || !up.body) return reply.code(up.status === 404 ? 404 : 502).send({ error: "image unavailable" });
    const type_ = (up.headers.get("content-type") ?? "").split(";")[0]!.trim().toLowerCase();
    if (!ALLOWED_PROXY_TYPES.has(type_)) {
      await up.body.cancel();
      return reply.code(502).send({ error: "unexpected content type from Jellyfin" });
    }
    return hardened(reply)
      .header("content-type", type_)
      .header("cache-control", "max-age=86400")
      .send(Readable.fromWeb(up.body as import("node:stream/web").ReadableStream));
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
