import { describe, expect, it } from "vitest";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { loadConfig, loadSecrets, loadSettings, saveSecrets } from "./config.js";

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), "vela-cfg-"));

describe("config", () => {
  it("missing file gives defaults", () => {
    const c = loadConfig(path.join(tmp(), "nope.toml"));
    expect(c.ui.effects).toBe("auto");
    expect(c.media.provider).toBe("mock");
    expect(c.devices).toEqual([]);
    expect(c.server.listen).toBe("127.0.0.1");
    expect(c.manifestDirs).toEqual(["/usr/share/vela/apps", "/etc/vela/apps"]);
  });

  it("parses a valid file", () => {
    const p = path.join(tmp(), "vela.toml");
    fs.writeFileSync(p, `[ui]\neffects = "lite"\n[[devices]]\nid="tv"\nname="TV"\nkind="tv"\nentity="media_player.tv"\n`);
    const c = loadConfig(p);
    expect(c.ui.effects).toBe("lite");
    expect(c.devices).toHaveLength(1);
  });

  it("invalid effects value throws naming ui.effects", () => {
    const p = path.join(tmp(), "vela.toml");
    fs.writeFileSync(p, `[ui]\neffects = "banana"\n`);
    expect(() => loadConfig(p)).toThrow(/ui\.effects/);
  });

  it("secrets saved with mode 0600", () => {
    const p = path.join(tmp(), "secrets.json");
    saveSecrets(p, { jellyfinToken: "t" });
    expect(fs.statSync(p).mode & 0o777).toBe(0o600);
    expect(loadSecrets(p)).toEqual({ jellyfinToken: "t" });
  });

  it("missing secrets give {}", () => {
    expect(loadSecrets(path.join(tmp(), "none.json"))).toEqual({});
  });

  it("a TOML syntax error names the path and position, never the file's text", () => {
    const p = path.join(tmp(), "vela.toml");
    fs.writeFileSync(p, `[ui]\neffects = "lite\nsecret = "hunter2"\n`);
    let msg = "";
    try {
      loadConfig(p);
    } catch (e) {
      msg = (e as Error).message;
    }
    expect(msg).toContain(p);
    expect(msg).toMatch(/line 2/);
    expect(msg).not.toContain("hunter2");
    expect(msg).not.toContain("lite");
    expect(msg).not.toContain("\n");
  });

  it("malformed secrets.json names the path, never the file's text", () => {
    const p = path.join(tmp(), "secrets.json");
    fs.writeFileSync(p, `{"jellyfinToken": "abc123secret" oops}`);
    let msg = "";
    try {
      loadSecrets(p);
    } catch (e) {
      msg = (e as Error).message;
    }
    expect(msg).toContain(p);
    expect(msg).toContain("not valid JSON");
    expect(msg).not.toContain("abc123secret");
    expect(msg).not.toContain("oops");
  });

  it("secrets.json that is not an object is rejected", () => {
    const p = path.join(tmp(), "secrets.json");
    fs.writeFileSync(p, `["x"]`);
    expect(() => loadSecrets(p)).toThrow(/secrets\.json/);
  });
});

describe("loadSettings (degraded mode)", () => {
  it("good files: no problem", () => {
    const d = tmp();
    fs.writeFileSync(path.join(d, "vela.toml"), `[ui]\neffects = "lite"\n`);
    fs.writeFileSync(path.join(d, "secrets.json"), `{"jellyfinToken":"t"}`);
    const s = loadSettings(path.join(d, "vela.toml"), path.join(d, "secrets.json"));
    expect(s.problem).toBeUndefined();
    expect(s.config.ui.effects).toBe("lite");
    expect(s.secrets).toEqual({ jellyfinToken: "t" });
  });

  it("an invalid vela.toml starts with defaults and says which file to fix", () => {
    const d = tmp();
    const cfg = path.join(d, "vela.toml");
    fs.writeFileSync(cfg, `[media]\nprovider = "jellyfin"\n[[devices]]\nid = "tv"\n[ui]\neffects = "banana"\n`);
    const s = loadSettings(cfg, path.join(d, "none.json"));
    expect(s.config.media.provider).toBe("mock");
    expect(s.config.devices).toEqual([]);
    expect(s.config.server.listen).toBe("127.0.0.1");
    expect(s.problem).toMatch(new RegExp(`^Vela couldn't read ${cfg.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}: .+\\. Fix the file and restart\\.$`));
    expect(s.problem).toContain("ui.effects");
  });

  it("a TOML syntax error is degraded too, without the file's text", () => {
    const d = tmp();
    const cfg = path.join(d, "vela.toml");
    fs.writeFileSync(cfg, `token = "s3cret\n`);
    const s = loadSettings(cfg, path.join(d, "none.json"));
    expect(s.config.media.provider).toBe("mock");
    expect(s.problem).toContain("vela.toml");
    expect(s.problem).not.toContain("s3cret");
  });

  it("a malformed secrets.json keeps the config, drops the secrets and says which file to fix", () => {
    const d = tmp();
    const cfg = path.join(d, "vela.toml");
    const sec = path.join(d, "secrets.json");
    fs.writeFileSync(cfg, `[ui]\neffects = "lite"\n`);
    fs.writeFileSync(sec, `{"homeAssistantToken": "tok-123",}`);
    const s = loadSettings(cfg, sec);
    expect(s.config.ui.effects).toBe("lite");
    expect(s.secrets).toEqual({});
    expect(s.problem).toMatch(/^Vela couldn't read .*secrets\.json: .+\. Fix the file and restart\.$/);
    expect(s.problem).not.toContain("tok-123");
  });
});
