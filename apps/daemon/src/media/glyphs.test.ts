import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, describe, expect, it, vi } from "vitest";
import { glyphUrl, loadGlyphs } from "./glyphs.js";

const assets = fileURLToPath(new URL("../../assets/glyphs", import.meta.url));
const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), "glyphs-"));
const decode = (url: string) => Buffer.from(url.slice(url.indexOf(",") + 1), "base64").toString("utf8");

describe("loadGlyphs", () => {
  afterEach(() => vi.restoreAllMocks());

  it("inlines each built-in glyph as a base64 SVG data URL, keyed by app id", () => {
    const g = loadGlyphs([{ id: "home", glyph: "home.svg" }, { id: "plex", glyph: "plex.svg" }], assets);
    expect([...g.keys()]).toEqual(["home", "plex"]);
    const url = g.get("home")!;
    expect(url.startsWith("data:image/svg+xml;base64,")).toBe(true);
    expect(decode(url)).toBe(fs.readFileSync(path.join(assets, "home.svg"), "utf8"));
  });

  it("a drop-in manifest's glyph is read from the manifest's own directory first", () => {
    const etc = tmp();
    fs.writeFileSync(path.join(etc, "kodi.svg"), "<svg id='kodi'/>");
    fs.writeFileSync(path.join(etc, "plex.svg"), "<svg id='my-plex'/>");
    const g = loadGlyphs([{ id: "kodi", glyph: "kodi.svg", dir: etc }, { id: "plex", glyph: "plex.svg", dir: etc }, { id: "home", glyph: "home.svg", dir: etc }], assets);
    expect(decode(g.get("kodi")!)).toBe("<svg id='kodi'/>");
    expect(decode(g.get("plex")!)).toBe("<svg id='my-plex'/>");
    // Not next to the manifest: falls back to the shared glyphs directory.
    expect(decode(g.get("home")!)).toBe(fs.readFileSync(path.join(assets, "home.svg"), "utf8"));
  });

  it("skips and logs missing files and names with a path or the wrong type", () => {
    const err = vi.spyOn(console, "error").mockImplementation(() => {});
    const dir = tmp();
    fs.writeFileSync(path.join(dir, "a.svg"), "<svg/>");
    fs.writeFileSync(path.join(dir, "b.png"), "png");
    const g = loadGlyphs(
      [
        { id: "a", glyph: "a.svg" },
        { id: "m", glyph: "missing.svg" },
        { id: "up", glyph: "../a.svg" },
        { id: "b", glyph: "b.png" },
      ],
      dir,
    );
    expect([...g.keys()]).toEqual(["a"]);
    const logged = err.mock.calls.map((c) => c.join(" "));
    expect(logged.some((l) => l.includes("missing.svg") && l.includes("not found"))).toBe(true);
    expect(logged.some((l) => l.includes("../a.svg") && l.includes("rejected"))).toBe(true);
    expect(logged.some((l) => l.includes("b.png") && l.includes("rejected"))).toBe(true);
  });
});

describe("glyphUrl", () => {
  it("prefers the cached data URL and falls back to /api/glyphs", () => {
    const g = new Map([["a", "data:image/svg+xml;base64,AA=="]]);
    expect(glyphUrl(g, { id: "a", glyph: "a.svg" })).toBe("data:image/svg+xml;base64,AA==");
    expect(glyphUrl(g, { id: "b", glyph: "b.svg" })).toBe("/api/glyphs/b.svg");
    expect(glyphUrl(undefined, { id: "b", glyph: "b.svg" })).toBe("/api/glyphs/b.svg");
  });
});
