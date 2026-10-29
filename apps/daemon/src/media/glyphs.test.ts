import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { glyphUrl, loadGlyphs } from "./glyphs.js";

const assets = fileURLToPath(new URL("../../assets/glyphs", import.meta.url));

describe("loadGlyphs", () => {
  it("inlines each built-in glyph as a base64 SVG data URL", () => {
    const g = loadGlyphs(assets, ["home.svg", "plex.svg", "home.svg"]);
    expect([...g.keys()]).toEqual(["home.svg", "plex.svg"]);
    const url = g.get("home.svg")!;
    expect(url.startsWith("data:image/svg+xml;base64,")).toBe(true);
    const svg = Buffer.from(url.slice(url.indexOf(",") + 1), "base64").toString("utf8");
    expect(svg).toBe(fs.readFileSync(path.join(assets, "home.svg"), "utf8"));
  });

  it("skips missing files and names with a path or the wrong type", () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "glyphs-"));
    fs.writeFileSync(path.join(dir, "a.svg"), "<svg/>");
    fs.writeFileSync(path.join(dir, "b.png"), "png");
    const g = loadGlyphs(dir, ["a.svg", "missing.svg", "../a.svg", "b.png"]);
    expect([...g.keys()]).toEqual(["a.svg"]);
  });
});

describe("glyphUrl", () => {
  it("prefers the cached data URL and falls back to /api/glyphs", () => {
    const g = new Map([["a.svg", "data:image/svg+xml;base64,AA=="]]);
    expect(glyphUrl(g, "a.svg")).toBe("data:image/svg+xml;base64,AA==");
    expect(glyphUrl(g, "b.svg")).toBe("/api/glyphs/b.svg");
    expect(glyphUrl(undefined, "b.svg")).toBe("/api/glyphs/b.svg");
  });
});
