import { describe, expect, it } from "vitest";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { loadManifests } from "./manifests.js";

const builtin = fileURLToPath(new URL("../manifests", import.meta.url));
const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), "vela-man-"));
const valid = (id: string, name: string) =>
  JSON.stringify({ id, name, glyph: `${id}.svg`, brandColor: "oklch(0.7 0.1 200)", kind: "command", command: ["true"], windowAppId: id });

describe("manifests", () => {
  it("loads the six built-ins", () => {
    const m = loadManifests([builtin]);
    expect(m.map((x) => x.id).sort()).toEqual(["crunchyroll", "home", "jellyfin", "plex", "steam", "youtube"]);
    expect(m.find((x) => x.id === "steam")?.homePassthrough).toBe(true);
    expect(m.find((x) => x.id === "jellyfin")?.feature).toEqual({ kind: "jellyfin-resume" });
  });

  it("/etc override replaces by id", () => {
    const etc = tmp();
    fs.writeFileSync(path.join(etc, "plex.json"), valid("plex", "My Plex"));
    const m = loadManifests([builtin, etc]);
    expect(m.filter((x) => x.id === "plex")).toHaveLength(1);
    expect(m.find((x) => x.id === "plex")?.name).toBe("My Plex");
  });

  it("records the directory each manifest came from", () => {
    const etc = tmp();
    fs.writeFileSync(path.join(etc, "plex.json"), valid("plex", "My Plex"));
    const m = loadManifests([builtin, etc]);
    expect(m.find((x) => x.id === "plex")?.dir).toBe(etc);
    expect(m.find((x) => x.id === "steam")?.dir).toBe(builtin);
  });

  it("invalid manifest is skipped, others load", () => {
    const d = tmp();
    fs.writeFileSync(path.join(d, "bad.json"), `{"id":"bad"}`);
    fs.writeFileSync(path.join(d, "junk.json"), `not json`);
    fs.writeFileSync(path.join(d, "ok.json"), valid("ok", "Ok"));
    const m = loadManifests([d, path.join(d, "missing-dir")]);
    expect(m.map((x) => x.id)).toEqual(["ok"]);
  });

  it("every built-in glyph file exists", () => {
    for (const m of loadManifests([builtin])) {
      expect(fs.existsSync(fileURLToPath(new URL(`../assets/glyphs/${m.glyph}`, import.meta.url)))).toBe(true);
    }
  });
});
