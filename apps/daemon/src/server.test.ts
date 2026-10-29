import { afterEach, describe, expect, it } from "vitest";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { buildServer } from "./server.js";
import { loadConfig } from "./config.js";
import { EventBus } from "./server.js";
import type { AppRunner } from "./session/appRunner.js";
import { NoTransmitter, NotFound } from "./devices/registry.js";
import { TvUnreachable } from "./devices/homeAssistant.js";
import { UnsupportedProtocol } from "./devices/ir/encode.js";

const fakeRunner = {
  launch: async (id: string) => (id === "plex" ? { ok: true as const } : { ok: false as const, reason: "installing" as const }),
  goHome: async () => {},
  current: () => "home",
} as unknown as AppRunner;
const services = () => ({ config: loadConfig("/nonexistent/vela.toml"), manifests: [], runner: fakeRunner, bus: new EventBus() });

const H = { host: "127.0.0.1:7300" };

describe("server", () => {
  afterEach(() => {
    delete process.env.VELA_LAUNCHER_DIR;
  });

  it("GET /api/health returns {ok:true}", async () => {
    const app = buildServer(services());
    const res = await app.inject({ method: "GET", url: "/api/health", headers: H });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({ ok: true });
  });

  it("serves the launcher dir when it exists", async () => {
    const d = fs.mkdtempSync(path.join(os.tmpdir(), "vela-ui-"));
    fs.writeFileSync(path.join(d, "index.html"), "<h1>hi</h1>");
    process.env.VELA_LAUNCHER_DIR = d;
    const app = buildServer(services());
    const res = await app.inject({ method: "GET", url: "/", headers: H });
    expect(res.statusCode).toBe(200);
    expect(res.body).toContain("<h1>hi</h1>");
  });

  it("starts without a launcher dir", async () => {
    process.env.VELA_LAUNCHER_DIR = "/nonexistent/launcher";
    const app = buildServer(services());
    expect((await app.inject({ url: "/api/health", headers: H })).statusCode).toBe(200);
  });

  it("POST /api/launch returns 200 or 409 with reason", async () => {
    const app = buildServer(services());
    const ok = await app.inject({ method: "POST", url: "/api/launch", payload: { id: "plex" }, headers: H });
    expect(ok.statusCode).toBe(200);
    const no = await app.inject({ method: "POST", url: "/api/launch", payload: { id: "x" }, headers: H });
    expect(no.statusCode).toBe(409);
    expect(no.json()).toEqual({ reason: "installing" });
  });

  it("POST /api/play: jellyfin key plays, mock key is 409 with a toast", async () => {
    const played: string[] = [];
    const sv = { ...services(), play: async (id: string) => void played.push(id) };
    const toasts: unknown[] = [];
    sv.bus.subscribe((e) => toasts.push(e));
    const app = buildServer(sv);
    const ok = await app.inject({ method: "POST", url: "/api/play", payload: { key: "jellyfin:abc" }, headers: H });
    expect(ok.statusCode).toBe(200);
    expect(played).toEqual(["abc"]);
    const mock = await app.inject({ method: "POST", url: "/api/play", payload: { key: "mock:sintel" }, headers: H });
    expect(mock.statusCode).toBe(409);
    expect(mock.json()).toEqual({ reason: "mock" });
    expect(toasts).toEqual([{ type: "toast", message: "Mock titles can't be played. Connect Jellyfin in /etc/vela/vela.toml." }]);
    const bad = await app.inject({ method: "POST", url: "/api/play", payload: { key: "nope" }, headers: H });
    expect(bad.statusCode).toBe(400);
  });

  it("POST /api/home/go succeeds", async () => {
    const app = buildServer(services());
    const res = await app.inject({ method: "POST", url: "/api/home/go", payload: {}, headers: H });
    expect(res.statusCode).toBe(200);
  });

  it("WS /api/events broadcasts bus events", async () => {
    const svc = services();
    const app = buildServer(svc);
    await app.ready();
    const ws = await app.injectWS("/api/events", { headers: H });
    const got = new Promise<string>((r) => ws.once("message", (d) => r(String(d))));
    svc.bus.emit({ type: "toast", message: "hi" });
    expect(JSON.parse(await got)).toEqual({ type: "toast", message: "hi" });
    ws.terminate();
    await app.close();
  });

  it("rejects foreign Origin with 403", async () => {
    const app = buildServer(services());
    const res = await app.inject({ method: "POST", url: "/api/home/go", payload: {}, headers: { ...H, origin: "https://evil.example" } });
    expect(res.statusCode).toBe(403);
  });

  it("rejects bad Host with 403", async () => {
    const app = buildServer(services());
    const res = await app.inject({ method: "GET", url: "/api/health", headers: { host: "evil.example:7300" } });
    expect(res.statusCode).toBe(403);
  });

  it("rejects form-encoded POST with 415", async () => {
    const app = buildServer(services());
    const res = await app.inject({ method: "POST", url: "/api/home/go", payload: "a=b", headers: { ...H, "content-type": "application/x-www-form-urlencoded" } });
    expect(res.statusCode).toBe(415);
  });

  it("accepts curl-style request without Origin, and the launcher origin", async () => {
    const app = buildServer(services());
    const a = await app.inject({ method: "POST", url: "/api/home/go", payload: "{}", headers: { ...H, "content-type": "application/json" } });
    expect(a.statusCode).toBe(200);
    const b = await app.inject({ method: "POST", url: "/api/home/go", payload: {}, headers: { ...H, origin: "http://127.0.0.1:7300" } });
    expect(b.statusCode).toBe(200);
  });

  it("rejects WS upgrade with foreign Origin", async () => {
    const app = buildServer(services());
    await app.ready();
    await expect(app.injectWS("/api/events", { headers: { ...H, origin: "https://evil.example" } })).rejects.toThrow();
    await app.close();
  });

  it("EventBus keeps delivering when a subscriber throws", () => {
    const bus = new EventBus();
    const got: string[] = [];
    bus.subscribe(() => { throw new Error("boom"); });
    bus.subscribe((e) => got.push(e.type));
    bus.emit({ type: "home" });
    expect(got).toEqual(["home"]);
  });

  it("guard applies to percent-encoded api path", async () => {
    const app = buildServer(services());
    const res = await app.inject({ method: "POST", url: "/%61pi/launch", payload: { id: "plex" }, headers: { ...H, origin: "https://evil.example" } });
    expect(res.statusCode).toBe(403);
  });

  it("guard applies to double-slash path", async () => {
    const app = buildServer(services());
    const res = await app.inject({ method: "POST", url: "//api/home/go", payload: {}, headers: { host: "evil.example:7300" } });
    expect(res.statusCode).toBe(403);
  });

  it("static launcher GET with good Host passes", async () => {
    const d = fs.mkdtempSync(path.join(os.tmpdir(), "vela-ui-"));
    fs.writeFileSync(path.join(d, "index.html"), "<h1>hi</h1>");
    process.env.VELA_LAUNCHER_DIR = d;
    const app = buildServer(services());
    expect((await app.inject({ method: "GET", url: "/", headers: H })).statusCode).toBe(200);
    expect((await app.inject({ method: "GET", url: "/", headers: { host: "evil.example:7300" } })).statusCode).toBe(403);
  });

  describe("devices", () => {
    const devs = (run: (id: string, c: string) => Promise<void>) => ({
      list: () => [{ id: "tv", name: "TV", kind: "tv" as const, commands: ["mute"] }],
      run,
    });
    const post = (app: ReturnType<typeof buildServer>, url = "/api/devices/tv/mute") => app.inject({ method: "POST", url, payload: {}, headers: H });

    it("GET /api/devices lists devices", async () => {
      const app = buildServer({ ...services(), devices: devs(async () => {}) });
      const res = await app.inject({ method: "GET", url: "/api/devices", headers: H });
      expect(res.json()).toEqual([{ id: "tv", name: "TV", kind: "tv", commands: ["mute"] }]);
    });

    it("GET /api/devices is empty when none are configured", async () => {
      const res = await buildServer(services()).inject({ method: "GET", url: "/api/devices", headers: H });
      expect(res.json()).toEqual([]);
    });

    it("POST runs the command and returns 204", async () => {
      const calls: string[] = [];
      const app = buildServer({ ...services(), devices: devs(async (i, c) => void calls.push(`${i}/${c}`)) });
      expect((await post(app)).statusCode).toBe(204);
      expect(calls).toEqual(["tv/mute"]);
    });

    it.each([
      [new NotFound("command"), 404, "Unknown command."],
      [new TvUnreachable(), 502, "The TV didn't respond. Check that it's on the network."],
      [new NoTransmitter(), 503, "No IR transmitter found. Plug in the IR dongle or blaster and try again."],
      [new UnsupportedProtocol("RC5"), 422, "IR protocol RC5 isn't supported yet."],
      [new Error("boom"), 500, "boom"],
    ])("maps %s to %i", async (err, code, msg) => {
      const app = buildServer({ ...services(), devices: devs(async () => { throw err; }) });
      const res = await post(app);
      expect(res.statusCode).toBe(code);
      expect(res.json()).toEqual({ error: msg });
    });

    it("POST without a devices service is 404", async () => {
      expect((await post(buildServer(services()))).statusCode).toBe(404);
    });
  });

  describe("perf", () => {
    const good = { frames: 1800, p50Ms: 16.7, p95Ms: 18.2, droppedPct: 0.4, effects: "full", width: 3840, height: 2160 };
    const postPerf = (app: ReturnType<typeof buildServer>, payload: unknown) =>
      app.inject({ method: "POST", url: "/api/perf", payload: payload as object, headers: { ...H, "content-type": "application/json" } });

    it("GET starts with no report and not armed", async () => {
      const res = await buildServer(services()).inject({ method: "GET", url: "/api/perf", headers: H });
      expect(res.statusCode).toBe(200);
      expect(res.json()).toEqual({ report: null, armed: false });
    });

    it("POST stores the latest report, which GET returns", async () => {
      const app = buildServer(services());
      expect((await postPerf(app, good)).statusCode).toBe(204);
      expect((await postPerf(app, { ...good, p95Ms: 30, effects: "lite" })).statusCode).toBe(204);
      const res = await app.inject({ method: "GET", url: "/api/perf", headers: H });
      expect(res.json()).toEqual({ report: { ...good, p95Ms: 30, effects: "lite" }, armed: false });
    });

    it("POST drops unknown fields", async () => {
      const app = buildServer(services());
      await postPerf(app, { ...good, extra: "x" });
      const res = await app.inject({ method: "GET", url: "/api/perf", headers: H });
      expect(res.json().report).toEqual(good);
    });

    it.each([
      ["a missing field", { ...good, p95Ms: undefined }],
      ["a string number", { ...good, frames: "1800" }],
      ["a negative number", { ...good, droppedPct: -1 }],
      ["an unknown effects mode", { ...good, effects: "auto" }],
      ["a zero width", { ...good, width: 0 }],
      ["a fractional frame count", { ...good, frames: 1.5 }],
      ["a non-object body", [1, 2]],
    ])("POST rejects %s with 400", async (_name, body) => {
      const app = buildServer(services());
      const res = await postPerf(app, body);
      expect(res.statusCode).toBe(400);
      expect(res.json()).toHaveProperty("error");
      expect((await app.inject({ method: "GET", url: "/api/perf", headers: H })).json().report).toBeNull();
    });

    const arm = (app: ReturnType<typeof buildServer>, headers: Record<string, string> = { "content-type": "application/json" }) =>
      app.inject({ method: "POST", url: "/api/perf/arm", payload: "{}", headers: { ...H, ...headers } });

    it("GET /api/perf?arm=1 does not arm", async () => {
      const s = services();
      const events: unknown[] = [];
      s.bus.subscribe((e) => events.push(e));
      const app = buildServer(s);
      const res = await app.inject({ method: "GET", url: "/api/perf?arm=1", headers: H });
      expect(res.json()).toEqual({ report: null, armed: false });
      expect(events).toEqual([]);
    });

    it("POST /api/perf/arm arms and emits perf-arm, clearing the report; the next report disarms", async () => {
      const s = services();
      const events: unknown[] = [];
      s.bus.subscribe((e) => events.push(e));
      const app = buildServer(s);
      await postPerf(app, good);
      const armed = await arm(app);
      expect(armed.statusCode).toBe(200);
      expect(armed.json()).toEqual({ report: null, armed: true });
      expect(events).toEqual([{ type: "perf-arm" }]);
      expect((await app.inject({ method: "GET", url: "/api/perf", headers: H })).json()).toEqual({ report: null, armed: true });
      await postPerf(app, good);
      expect((await app.inject({ method: "GET", url: "/api/perf", headers: H })).json()).toEqual({ report: good, armed: false });
    });

    it("POST /api/perf/arm with form content-type → 415", async () => {
      const s = services();
      const events: unknown[] = [];
      s.bus.subscribe((e) => events.push(e));
      const app = buildServer(s);
      const res = await arm(app, { "content-type": "application/x-www-form-urlencoded" });
      expect(res.statusCode).toBe(415);
      expect(events).toEqual([]);
      expect((await app.inject({ method: "GET", url: "/api/perf", headers: H })).json().armed).toBe(false);
    });
  });
});
