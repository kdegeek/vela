import { describe, expect, it } from "vitest";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { loadConfig, loadSecrets, saveSecrets } from "./config.js";

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
});
