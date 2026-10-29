import fs from "node:fs";
import path from "node:path";

const SAFE_SVG = /^[A-Za-z0-9][A-Za-z0-9._-]*\.svg$/;

/**
 * Reads each app glyph once, at startup, and returns `data:image/svg+xml;base64,…` URLs keyed by
 * file name. The launcher uses them directly as CSS masks, so a tile never waits on (or loses) a
 * request for its glyph while Chromium is starting. Missing or unsafe names are left out.
 */
export function loadGlyphs(dir: string, files: Iterable<string>): Map<string, string> {
  const out = new Map<string, string>();
  for (const file of new Set(files)) {
    if (!SAFE_SVG.test(file)) continue;
    try {
      out.set(file, `data:image/svg+xml;base64,${fs.readFileSync(path.join(dir, file)).toString("base64")}`);
    } catch (e) {
      console.error(`vela: glyph ${file} unavailable: ${(e as Error).message}`);
    }
  }
  return out;
}

/** The cached data URL, or the /api/glyphs path for a glyph that wasn't loaded. */
export function glyphUrl(glyphs: ReadonlyMap<string, string> | undefined, file: string): string {
  return glyphs?.get(file) ?? `/api/glyphs/${file}`;
}
