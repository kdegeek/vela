import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { checkConfigFile, loadConfig } from "../config.js";
import { setupToml, stageAndCommit } from "./stage.js";

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), "vela-stage-"));
const KEY = { key: "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIM3a/gFsAyb6ZO/J0a6ccNAsC6JZsMf3aIWNvcWfZgU9 me", type: "ssh-ed25519", fingerprint: "SHA256:x" };

describe("setup staging", () => {
  it("writes a vela.toml the daemon's schema loads, for Jellyfin and for skip", () => {
    const d = tmp();
    const f = path.join(d, "vela.toml");
    fs.writeFileSync(f, setupToml({ provider: "jellyfin", url: 'http://jf.lan:8096/a"b', server: "Den" }));
    expect(loadConfig(f).media).toEqual({ provider: "jellyfin", jellyfin: { url: 'http://jf.lan:8096/a"b' } });
    fs.writeFileSync(f, setupToml({ provider: "mock" }));
    expect(loadConfig(f).media).toEqual({ provider: "mock" });
  });

  it("stages vela.toml and authorized_keys, then touches commit; nothing for root to read is left half-written", () => {
    const d = path.join(tmp(), "setup");
    stageAndCommit(d, { media: { provider: "mock" }, keys: [KEY] });
    expect(fs.readdirSync(d).sort()).toEqual(["authorized_keys", "commit", "vela.toml"]);
    expect(fs.readFileSync(path.join(d, "authorized_keys"), "utf8")).toBe(`${KEY.key}\n`);
    expect(fs.statSync(d).mode & 0o777).toBe(0o700);
    expect(fs.statSync(path.join(d, "authorized_keys")).mode & 0o777).toBe(0o600);
    expect(checkConfigFile(path.join(d, "vela.toml"))).toBeUndefined();
  });

  it("with SSH skipped, removes any earlier authorized_keys", () => {
    const d = tmp();
    stageAndCommit(d, { media: { provider: "mock" }, keys: [KEY] });
    stageAndCommit(d, { media: { provider: "mock" }, keys: [] });
    expect(fs.readdirSync(d).sort()).toEqual(["commit", "vela.toml"]);
  });
});

describe("check-config", () => {
  it("passes a valid file and names the problem otherwise, including a missing file", () => {
    const d = tmp();
    const f = path.join(d, "vela.toml");
    expect(checkConfigFile(f)).toMatch(/does not exist/);
    fs.writeFileSync(f, '[media]\nprovider = "jellyfin"\n[media.jellyfin]\nurl = "http://x"\n');
    expect(checkConfigFile(f)).toBeUndefined();
    fs.writeFileSync(f, '[media]\nprovider = "plex"\n');
    expect(checkConfigFile(f)).toMatch(/media\.provider/);
    fs.writeFileSync(f, "[media\n");
    expect(checkConfigFile(f)).toMatch(/Invalid config .*line 1/);
  });
});
