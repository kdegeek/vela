import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { API, SETUP_API, type VelaEvent } from "@vela/shared";
import { BODY_LIMIT, buildSetupServer, setupUrl, startSetupMode, type SetupDeps } from "./listener.js";
import { SetupSession } from "./state.js";

const PORT = 7301;
const KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIM3a/gFsAyb6ZO/J0a6ccNAsC6JZsMf3aIWNvcWfZgU9";
const KEY_FP = "SHA256:S9mp+UD5gdC3LzRIgablt17qpD3KLJatMvHQUZCEW7I";
const PASSWORD = "correct-horse-Battery-9";

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), "vela-setup-"));

/** A Jellyfin server named Den with user vela / PASSWORD, plus GitHub's .keys for "octocat". */
function fakeFetch() {
  return vi.fn(async (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
    const u = String(input);
    if (u === "http://jf.lan:8096/System/Info/Public") return Response.json({ ServerName: "Den", Id: "srv1" });
    if (u === "http://jf.lan:8096/Users/AuthenticateByName") {
      const b = JSON.parse(String(init?.body)) as { Username: string; Pw: string };
      if (b.Username === "vela" && b.Pw === PASSWORD) return Response.json({ AccessToken: "tok123", User: { Id: "user1" } });
      return new Response("", { status: 401 });
    }
    if (u === "https://github.com/octocat.keys") return new Response(`${KEY}\n`);
    throw new TypeError("fetch failed");
  });
}

let dir: string;
let deps: SetupDeps;
let session: SetupSession;
let changes: number;

beforeEach(() => {
  dir = tmp();
  changes = 0;
  session = new SetupSession(() => changes++);
  deps = {
    session,
    port: PORT,
    secretsPath: path.join(dir, "secrets.json"),
    stagingDir: path.join(dir, "setup"),
    fetch: fakeFetch() as unknown as typeof fetch,
    discover: async () => [{ name: "Den", url: "http://jf.lan:8096" }],
    addresses: () => ["192.168.1.20", "fe80::1"],
    hostname: "vela-box",
  };
});

const H = () => ({ host: `192.168.1.20:${PORT}`, authorization: `Pin ${session.pin.current()}` });
const post = (app: ReturnType<typeof buildSetupServer>, url: string, payload: unknown, headers: Record<string, string> = H()) =>
  app.inject({ method: "POST", url, payload: payload as object, headers });

describe("setup listener isolation", () => {
  it("answers none of the main API's routes, even with a phone page present", async () => {
    const page = tmp();
    fs.writeFileSync(path.join(page, "index.html"), "<h1>setup</h1>");
    const app = buildSetupServer({ ...deps, staticDir: page });
    const paths = [...Object.values(API).map((p) => p.replace(":id", "tv").replace(":command", "power")), "/api/glyphs/home.svg", "/api/art/mock/p-bbb.jpg", "/api/art/jellyfin/abcdef12/Primary?w=704"];
    for (const url of paths) {
      for (const method of ["GET", "POST"] as const) {
        const res = await app.inject({ method, url, headers: H(), ...(method === "POST" ? { payload: {} } : {}) });
        expect(res.statusCode, `${method} ${url}`).toBe(404);
      }
    }
    const routes = app.printRoutes({ commonPrefix: false });
    expect(routes.split("\n").filter((l) => l.includes("api/") && !l.includes("setup/api/"))).toEqual([]);
    expect((await app.inject({ url: "/", headers: H() })).body).toContain("<h1>setup</h1>");
  });

  it("starts without a phone page directory", async () => {
    const app = buildSetupServer({ ...deps, staticDir: "/nonexistent/setup" });
    expect((await app.inject({ url: SETUP_API.state, headers: H() })).statusCode).toBe(200);
  });
});

describe("setup listener guard", () => {
  it("accepts the box's IPs, <hostname>.local and localhost; refuses any other Host", async () => {
    const app = buildSetupServer(deps);
    const pin = `Pin ${session.pin.current()}`;
    for (const host of [`192.168.1.20:${PORT}`, `[fe80::1]:${PORT}`, `vela-box.local:${PORT}`, `VELA-BOX.local:${PORT}`, `localhost:${PORT}`, `127.0.0.1:${PORT}`]) {
      expect((await app.inject({ url: SETUP_API.state, headers: { host, authorization: pin } })).statusCode, host).toBe(200);
    }
    for (const host of ["evil.example", `evil.example:${PORT}`, `192.168.1.21:${PORT}`, "192.168.1.20", `192.168.1.20:7300`, ""]) {
      expect((await app.inject({ url: SETUP_API.state, headers: { host, authorization: pin } })).statusCode, host).toBe(403);
      expect((await app.inject({ url: "/", headers: { host } })).statusCode, host).toBe(403);
    }
  });

  it("refuses an Origin that isn't the Host", async () => {
    const app = buildSetupServer(deps);
    expect((await post(app, SETUP_API.sshSkip, {}, { ...H(), origin: "http://evil.example" })).statusCode).toBe(403);
    expect((await post(app, SETUP_API.sshSkip, {}, { ...H(), origin: `http://vela-box.local:${PORT}` })).statusCode).toBe(403);
    expect((await post(app, SETUP_API.sshSkip, {}, { ...H(), origin: `http://192.168.1.20:${PORT}` })).statusCode).toBe(200);
  });

  it("needs JSON POSTs and caps bodies at 16 KB", async () => {
    const app = buildSetupServer(deps);
    const form = await app.inject({ method: "POST", url: SETUP_API.sshSkip, payload: "a=1", headers: { ...H(), "content-type": "application/x-www-form-urlencoded" } });
    expect(form.statusCode).toBe(415);
    const big = await post(app, SETUP_API.sshKeys, { keys: ["x".repeat(BODY_LIMIT)] });
    expect(big.statusCode).toBe(413);
  });

  it("needs the PIN on every /setup/api call", async () => {
    const app = buildSetupServer(deps);
    for (const auth of [undefined, "Pin", "Bearer 123456", `Pin ${session.pin.current()}0`]) {
      const res = await app.inject({ url: SETUP_API.state, headers: { host: `localhost:${PORT}`, ...(auth ? { authorization: auth } : {}) } });
      expect(res.statusCode, String(auth)).toBe(401);
    }
  });

  it("five wrong PINs: a new PIN for the TV and 429 with Retry-After, even for the new PIN", async () => {
    const app = buildSetupServer(deps);
    const first = session.pin.current();
    const wrong = first === "000000" ? "111111" : "000000";
    for (let i = 0; i < 4; i++) expect((await app.inject({ url: SETUP_API.state, headers: { ...H(), authorization: `Pin ${wrong}` } })).statusCode).toBe(401);
    expect(changes).toBe(0);
    const fifth = await app.inject({ url: SETUP_API.state, headers: { ...H(), authorization: `Pin ${wrong}` } });
    expect(fifth.statusCode).toBe(429);
    expect(Number(fifth.headers["retry-after"])).toBe(30);
    expect(changes).toBe(1);
    const again = await app.inject({ url: SETUP_API.state, headers: H() });
    expect(again.statusCode).toBe(429);
    expect(again.json().error).toMatch(/new one/);
  });
});

describe("setup API", () => {
  it("runs the wizard: Jellyfin sign-in, GitHub keys, finish; stages root's files and keeps only the token", async () => {
    const logs: string[] = [];
    const spies = (["log", "error", "warn", "info", "debug"] as const).map((m) => vi.spyOn(console, m).mockImplementation((...a: unknown[]) => void logs.push(a.map(String).join(" "))));
    const writes = vi.spyOn(process.stdout, "write");
    const errWrites = vi.spyOn(process.stderr, "write");
    try {
      const app = buildSetupServer(deps);
      expect((await app.inject({ url: SETUP_API.state, headers: H() })).json()).toEqual({ step: "media", media: null, keys: [], sshSkipped: false });
      expect((await app.inject({ url: SETUP_API.jellyfinDiscover, headers: H() })).json()).toEqual({ servers: [{ name: "Den", url: "http://jf.lan:8096" }] });

      const bad = await post(app, SETUP_API.jellyfin, { url: "jf.lan:8096", username: "vela", password: "nope" });
      expect(bad.statusCode).toBe(422);
      expect(bad.json().error).toMatch(/didn't accept that username and password/);
      const down = await post(app, SETUP_API.jellyfin, { url: "http://other:8096", username: "vela", password: PASSWORD });
      expect(down.statusCode).toBe(502);
      expect(down.json().error).toMatch(/Couldn't reach a Jellyfin server at http:\/\/other:8096/);
      expect((await post(app, SETUP_API.jellyfin, { url: "ftp://jf", username: "vela", password: PASSWORD })).statusCode).toBe(400);
      // Malformed JSON that carries the password: the reply mustn't quote it.
      const garbled = await app.inject({ method: "POST", url: SETUP_API.jellyfin, payload: `{"password":"${PASSWORD}"`, headers: { ...H(), "content-type": "application/json" } });
      expect(garbled.statusCode).toBe(400);
      expect(garbled.body).not.toContain(PASSWORD);

      const ok = await post(app, SETUP_API.jellyfin, { url: "jf.lan:8096/", username: "vela", password: PASSWORD });
      expect(ok.statusCode).toBe(200);
      expect(ok.json()).toEqual({ server: "Den" });
      expect(JSON.parse(fs.readFileSync(deps.secretsPath, "utf8"))).toEqual({ jellyfinToken: "tok123", jellyfinUserId: "user1" });
      expect(fs.statSync(deps.secretsPath).mode & 0o777).toBe(0o600);
      expect(session.status(null)).toEqual({ pin: session.pin.current(), url: null, step: "ssh", jellyfinServer: "Den", keyCount: 0 });

      expect((await post(app, SETUP_API.finish, {})).statusCode).toBe(409);
      expect((await post(app, SETUP_API.sshGithub, { username: "../evil" })).statusCode).toBe(400);
      const gh = await post(app, SETUP_API.sshGithub, { username: "octocat" });
      expect(gh.json()).toEqual({ keys: [{ key: `${KEY} octocat@github`, type: "ssh-ed25519", fingerprint: KEY_FP, comment: "octocat@github" }] });
      const opt = await post(app, SETUP_API.sshKeys, { keys: [`command="sh" ${KEY}`] });
      expect(opt.statusCode).toBe(400);
      expect(opt.json().error).toMatch(/options/);
      const staged = await post(app, SETUP_API.sshKeys, { keys: [`${KEY} octocat@github`] });
      expect(staged.json()).toMatchObject({ step: "done", keys: [{ fingerprint: KEY_FP }] });

      const fin = await post(app, SETUP_API.finish, {});
      expect(fin.statusCode).toBe(200);
      expect(fin.json().step).toBe("committing");
      expect(fs.readdirSync(deps.stagingDir).sort()).toEqual(["authorized_keys", "commit", "vela.toml"]);
      expect(fs.readFileSync(path.join(deps.stagingDir, "authorized_keys"), "utf8")).toBe(`${KEY} octocat@github\n`);
      expect(fs.readFileSync(path.join(deps.stagingDir, "vela.toml"), "utf8")).toContain('url = "http://jf.lan:8096"');

      // The password is in no file the box wrote and in no log line.
      for (const f of fs.readdirSync(dir, { recursive: true }) as string[]) {
        const full = path.join(dir, f);
        if (fs.statSync(full).isFile()) expect(fs.readFileSync(full, "utf8"), f).not.toContain(PASSWORD);
      }
      for (const l of logs) expect(l).not.toContain(PASSWORD);
      for (const c of [...writes.mock.calls, ...errWrites.mock.calls]) expect(String(c[0])).not.toContain(PASSWORD);
    } finally {
      for (const s of spies) s.mockRestore();
      writes.mockRestore();
      errWrites.mockRestore();
    }
  });

  it("skip and skip: finish stages a mock vela.toml and no authorized_keys", async () => {
    const app = buildSetupServer(deps);
    expect((await post(app, SETUP_API.jellyfinSkip, {})).json().step).toBe("ssh");
    expect((await post(app, SETUP_API.sshSkip, {})).json()).toEqual({ step: "done", media: { provider: "mock" }, keys: [], sshSkipped: true });
    expect((await post(app, SETUP_API.finish, {})).json().step).toBe("committing");
    expect(fs.readdirSync(deps.stagingDir).sort()).toEqual(["commit", "vela.toml"]);
    expect(fs.readFileSync(path.join(deps.stagingDir, "vela.toml"), "utf8")).toContain('provider = "mock"');
    // Changing an answer after finishing reopens the session, so a refused commit can be retried.
    await post(app, SETUP_API.jellyfinSkip, {});
    expect(session.step()).toBe("done");
  });

  it("an empty key list asks for a key", async () => {
    const app = buildSetupServer(deps);
    const res = await post(app, SETUP_API.sshKeys, { keys: ["", "  "] });
    expect(res.statusCode).toBe(400);
    expect(res.json().error).toMatch(/at least one key/);
  });
});

describe("setup mode", () => {
  afterEach(() => vi.useRealTimers());

  it("the URL is the box's first IPv4 address, or null", () => {
    expect(setupUrl(7301, ["fe80::1", "10.0.0.7", "192.168.1.2"])).toBe("http://10.0.0.7:7301/");
    expect(setupUrl(7301, ["fe80::1"])).toBeNull();
  });

  it("follows the gate file: opens the listener with a PIN, closes it when root removes the gate", async () => {
    const port = await new Promise<number>((r) => {
      const s = net.createServer().listen(0, "127.0.0.1", () => {
        const p = (s.address() as net.AddressInfo).port;
        s.close(() => r(p));
      });
    });
    const d = tmp();
    const gatePath = path.join(d, "open");
    const events: VelaEvent[] = [];
    const mode = startSetupMode({ gatePath, port, secretsPath: path.join(d, "s.json"), stagingDir: path.join(d, "setup"), staticDir: "/nonexistent", emit: (e) => events.push(e), intervalMs: 20 });
    try {
      expect(mode.status()).toBeUndefined();
      expect(events).toEqual([{ type: "setup", setup: null }]);

      fs.writeFileSync(gatePath, "");
      await vi.waitFor(() => expect(mode.status()?.step).toBe("media"));
      const pin = mode.status()!.pin;
      const res = await vi.waitFor(async () => {
        const r = await fetch(`http://127.0.0.1:${port}${SETUP_API.state}`, { headers: { authorization: `Pin ${pin}` } });
        expect(r.status).toBe(200);
        return r;
      });
      expect(((await res.json()) as { step: string }).step).toBe("media");
      expect(events.at(-1)).toMatchObject({ type: "setup", setup: { pin, step: "media", keyCount: 0 } });

      fs.rmSync(gatePath);
      await vi.waitFor(() => expect(events.at(-1)).toEqual({ type: "setup", setup: null }));
      expect(mode.status()).toBeUndefined();
      await vi.waitFor(async () => expect(await fetch(`http://127.0.0.1:${port}/`).then(() => "open", () => "closed")).toBe("closed"));
    } finally {
      await mode.stop();
    }
  });
});
