import { afterEach, describe, expect, it } from "vitest";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { buildServer } from "./server.js";
import { loadConfig } from "./config.js";
import { EventBus } from "./server.js";
import type { AppRunner } from "./session/appRunner.js";

const fakeRunner = {
  launch: async (id: string) => (id === "plex" ? { ok: true as const } : { ok: false as const, reason: "installing" as const }),
  goHome: async () => {},
  current: () => "home",
} as unknown as AppRunner;
const services = () => ({ config: loadConfig("/nonexistent/vela.toml"), manifests: [], runner: fakeRunner, bus: new EventBus() });

describe("server", () => {
  afterEach(() => {
    delete process.env.VELA_LAUNCHER_DIR;
  });

  it("GET /api/health returns {ok:true}", async () => {
    const app = buildServer(services());
    const res = await app.inject({ method: "GET", url: "/api/health" });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({ ok: true });
  });

  it("serves the launcher dir when it exists", async () => {
    const d = fs.mkdtempSync(path.join(os.tmpdir(), "vela-ui-"));
    fs.writeFileSync(path.join(d, "index.html"), "<h1>hi</h1>");
    process.env.VELA_LAUNCHER_DIR = d;
    const app = buildServer(services());
    const res = await app.inject({ method: "GET", url: "/" });
    expect(res.statusCode).toBe(200);
    expect(res.body).toContain("<h1>hi</h1>");
  });

  it("starts without a launcher dir", async () => {
    process.env.VELA_LAUNCHER_DIR = "/nonexistent/launcher";
    const app = buildServer(services());
    expect((await app.inject({ url: "/api/health" })).statusCode).toBe(200);
  });

  it("POST /api/launch returns 200 or 409 with reason", async () => {
    const app = buildServer(services());
    const ok = await app.inject({ method: "POST", url: "/api/launch", payload: { id: "plex" } });
    expect(ok.statusCode).toBe(200);
    const no = await app.inject({ method: "POST", url: "/api/launch", payload: { id: "x" } });
    expect(no.statusCode).toBe(409);
    expect(no.json()).toEqual({ reason: "installing" });
  });

  it("POST /api/home/go succeeds", async () => {
    const app = buildServer(services());
    const res = await app.inject({ method: "POST", url: "/api/home/go" });
    expect(res.statusCode).toBe(200);
  });

  it("WS /api/events broadcasts bus events", async () => {
    const svc = services();
    const app = buildServer(svc);
    await app.ready();
    const ws = await app.injectWS("/api/events");
    const got = new Promise<string>((r) => ws.once("message", (d) => r(String(d))));
    svc.bus.emit({ type: "toast", message: "hi" });
    expect(JSON.parse(await got)).toEqual({ type: "toast", message: "hi" });
    ws.terminate();
    await app.close();
  });
});
