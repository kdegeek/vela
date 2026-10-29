import fs from "node:fs";
import os from "node:os";
import Fastify, { type FastifyInstance, type FastifyReply } from "fastify";
import fastifyStatic from "@fastify/static";
import { z } from "zod";
import { SETUP_API, type JellyfinServer, type SetupStatus, type VelaEvent } from "@vela/shared";
import { discoverJellyfin } from "./discovery.js";
import { fetchGithubKeys, KeyError, parseKeys } from "./githubKeys.js";
import { signInJellyfin } from "./jellyfinAuth.js";
import { stageAndCommit } from "./stage.js";
import { SetupError, SetupSession, watchGate } from "./state.js";

export const SETUP_PORT = Number(process.env.VELA_SETUP_PORT ?? 7301);
export const BODY_LIMIT = 16 * 1024;

/** The box's own non-loopback addresses (no IPv6 zone ids: browsers never put one in Host). */
export function boxAddresses(): string[] {
  return Object.values(os.networkInterfaces())
    .flat()
    .filter((a) => a && !a.internal && !a.address.includes("%"))
    .map((a) => a!.address);
}

/** The address the TV shows and the QR code encodes; null until the box has an IPv4 address. */
export function setupUrl(port: number, addresses: string[] = boxAddresses()): string | null {
  const v4 = addresses.find((a) => /^\d+\.\d+\.\d+\.\d+$/.test(a));
  return v4 ? `http://${v4}:${port}/` : null;
}

export interface SetupDeps {
  session: SetupSession;
  port: number;
  secretsPath: string;
  /** /var/lib/vela/setup: where vela.toml, authorized_keys and commit are staged for root. */
  stagingDir: string;
  /** Built phone page (apps/setup/dist); skipped when absent. */
  staticDir?: string;
  /** Used for GitHub and Jellyfin; tests inject one. */
  fetch?: typeof fetch;
  discover?: () => Promise<JellyfinServer[]>;
  /** Defaults to the box's addresses and hostname, read per request (DHCP may finish after start). */
  addresses?: () => string[];
  hostname?: string;
  /** Jellyfin DeviceId for the sign-in; defaults to the machine id, like the main client. */
  deviceId?: string;
}

const MESSAGES: Record<number, string> = {
  400: "The request wasn't valid. Reload the page and try again.",
  404: "Not found.",
  413: "That request is too large.",
  415: "Send JSON.",
};

// Fixed text in the page's own words; a field's value (the password) is never echoed back.
const body = <T extends z.ZodType>(schema: T, data: unknown, what: string): z.infer<T> => {
  const r = schema.safeParse(data);
  if (!r.success) throw new SetupError(400, what);
  return r.data;
};
const JellyfinBody = z.object({ url: z.string().min(1).max(2048), username: z.string().min(1).max(256), password: z.string().max(1024) });
const GithubBody = z.object({ username: z.string().max(100) });
const KeysBody = z.object({ keys: z.array(z.string().max(16_384)).max(64) });

/**
 * The setup listener: a Fastify instance separate from the main API, with its own guard and only the phone page and
 * /setup/api/*. None of the main API's routes are registered on it.
 */
export function buildSetupServer(d: SetupDeps): FastifyInstance {
  const app = Fastify({ logger: false, bodyLimit: BODY_LIMIT });
  const { session } = d;
  const addresses = d.addresses ?? boxAddresses;
  const local = `${(d.hostname ?? os.hostname()).split(".")[0]!.toLowerCase()}.local`;

  // Guard against other web pages driving the phone's session (CSRF, DNS rebinding): the request must address this
  // box by IP or <hostname>.local, a browser's Origin must be that same host, and POSTs must be JSON.
  app.addHook("onRequest", async (req, reply) => {
    const host = (req.headers.host ?? "").toLowerCase();
    const allowed = new Set([`localhost:${d.port}`, `127.0.0.1:${d.port}`, `${local}:${d.port}`]);
    for (const a of addresses()) allowed.add(a.includes(":") ? `[${a.toLowerCase()}]:${d.port}` : `${a}:${d.port}`);
    if (!allowed.has(host)) return reply.code(403).send({ error: "Open the address shown on the TV." });
    const origin = req.headers.origin;
    if (origin !== undefined && origin.toLowerCase() !== `http://${host}`) return reply.code(403).send({ error: "bad origin" });
    if (req.method === "POST" && !(req.headers["content-type"] ?? "").toLowerCase().startsWith("application/json")) {
      return reply.code(415).send({ error: MESSAGES[415] });
    }
  });
  app.addHook("onSend", async (_req, reply) => {
    reply
      .header("cache-control", "no-store")
      .header("x-content-type-options", "nosniff")
      .header("referrer-policy", "no-referrer")
      .header("content-security-policy", "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
  });

  app.setErrorHandler((err: { statusCode?: number; code?: string; name?: string }, _req, reply) => {
    const code = err.statusCode && err.statusCode >= 400 && err.statusCode < 500 ? err.statusCode : 500;
    // Only the error's name and code: a body parser's message can quote the request (and so the password).
    if (code === 500) console.error("vela: setup request failed:", err.code ?? err.name);
    return reply.code(code).send({ error: MESSAGES[code] ?? "Something went wrong on the box. Try again." });
  });
  app.setNotFoundHandler((_req, reply) => reply.code(404).send({ error: MESSAGES[404] }));

  app.register(async (api) => {
    api.addHook("onRequest", async (req, reply) => {
      const m = /^Pin (\S{1,32})$/.exec(req.headers.authorization ?? "");
      if (!m) return reply.code(401).send({ error: "Enter the PIN shown on the TV." });
      const r = session.pin.check(m[1]!);
      if (r === "locked") {
        const s = session.pin.retryAfter();
        return reply.code(429).header("retry-after", String(s)).send({ error: `Too many wrong PINs. The TV shows a new one; try again in ${s} seconds.` });
      }
      if (r === "wrong") return reply.code(401).send({ error: "That PIN is wrong. Check the PIN on the TV." });
    });

    const handle = async (reply: FastifyReply, fn: () => unknown) => {
      try {
        return await fn();
      } catch (e) {
        if (e instanceof SetupError) return reply.code(e.status).send({ error: e.message });
        if (e instanceof KeyError) return reply.code(400).send({ error: e.message });
        throw e;
      }
    };

    api.get(SETUP_API.state, async () => session.progress());

    api.get(SETUP_API.jellyfinDiscover, async () => ({ servers: await (d.discover ?? discoverJellyfin)() }));

    api.post(SETUP_API.jellyfin, (req, reply) =>
      handle(reply, async () => {
        const b = body(JellyfinBody, req.body, "Enter the server address, username and password.");
        const r = await signInJellyfin({ ...b, secretsPath: d.secretsPath, fetch: d.fetch, deviceId: d.deviceId });
        session.setMedia({ provider: "jellyfin", url: r.url, server: r.server });
        return { server: r.server };
      }),
    );

    api.post(SETUP_API.jellyfinSkip, async () => {
      session.setMedia({ provider: "mock" });
      return session.progress();
    });

    api.post(SETUP_API.sshGithub, (req, reply) =>
      handle(reply, async () => {
        const b = body(GithubBody, req.body, "Enter a GitHub username.");
        return { keys: await fetchGithubKeys(b.username.trim(), d.fetch) };
      }),
    );

    api.post(SETUP_API.sshKeys, (req, reply) =>
      handle(reply, () => {
        const b = body(KeysBody, req.body, "Paste one or more SSH public keys.");
        const keys = parseKeys(b.keys.flatMap((k) => k.split(/\r?\n/)));
        if (!keys.length) throw new SetupError(400, "Add at least one key, or skip this step.");
        session.setKeys(keys);
        return session.progress();
      }),
    );

    api.post(SETUP_API.sshSkip, async () => {
      session.skipSsh();
      return session.progress();
    });

    api.post(SETUP_API.finish, (_req, reply) =>
      handle(reply, () => {
        const answers = session.answers();
        try {
          stageAndCommit(d.stagingDir, answers);
        } catch (e) {
          console.error("vela: setup could not stage the settings:", (e as Error).message);
          throw new SetupError(500, "Vela couldn't save the settings on the box. Try again; if it keeps failing, restart the box.");
        }
        session.markFinished();
        return session.progress();
      }),
    );
  });

  if (d.staticDir && fs.existsSync(d.staticDir)) app.register(fastifyStatic, { root: d.staticDir });
  return app;
}

export interface SetupModeOptions {
  gatePath: string;
  port: number;
  secretsPath: string;
  stagingDir: string;
  staticDir: string;
  emit: (e: VelaEvent) => void;
  intervalMs?: number;
}

/**
 * Setup mode follows the gate file: while it exists the setup listener runs on 0.0.0.0:<port> with a fresh PIN;
 * when root removes it (setup committed) the listener closes. Every change to what the TV shows is emitted as a
 * `setup` event (null when setup mode ends).
 */
export function startSetupMode(o: SetupModeOptions) {
  let session: SetupSession | undefined;
  let app: FastifyInstance | undefined;
  let starting = false;
  let lastUrl: string | null = null;
  let lastError: string | undefined;

  const status = (): SetupStatus | undefined => session?.status(setupUrl(o.port));
  const publish = () => {
    const s = status();
    lastUrl = s?.url ?? null;
    o.emit({ type: "setup", setup: s ?? null });
  };
  const close = (a: FastifyInstance) => a.close().catch((e) => console.error("vela: setup listener close:", (e as Error).message));

  const start = (s: SetupSession) => {
    starting = true;
    const a = buildSetupServer({ session: s, port: o.port, secretsPath: o.secretsPath, stagingDir: o.stagingDir, staticDir: o.staticDir });
    a.listen({ host: "0.0.0.0", port: o.port }).then(
      () => {
        starting = false;
        lastError = undefined;
        if (session === s) {
          app = a;
          console.log(`vela: setup mode, listening on 0.0.0.0:${o.port}`);
        } else void close(a);
      },
      (e) => {
        starting = false;
        // Retried on the next tick; logged once, not every second.
        const msg = (e as Error).message;
        if (msg !== lastError) console.error("vela: setup listener:", msg);
        lastError = msg;
        void close(a);
      },
    );
  };

  const gate = watchGate(
    o.gatePath,
    (open) => {
      if (open) {
        session = new SetupSession(publish);
      } else {
        session = undefined;
        if (app) {
          void close(app);
          console.log("vela: setup mode ended");
        }
        app = undefined;
      }
      publish();
    },
    () => {
      if (session && !app && !starting) start(session);
      if (session && setupUrl(o.port) !== lastUrl) publish();
    },
    o.intervalMs,
  );

  return {
    status,
    stop: async () => {
      gate.stop();
      session = undefined;
      if (app) await close(app);
      app = undefined;
    },
  };
}
