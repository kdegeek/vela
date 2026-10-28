import { afterEach, describe, expect, it } from "vitest";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { buildServer } from "./server.js";
import { loadConfig } from "./config.js";

const services = () => ({ config: loadConfig("/nonexistent/vela.toml"), manifests: [] });

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
});
