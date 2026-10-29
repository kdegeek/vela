import fs from "node:fs";
import path from "node:path";

const SAFE_SVG = /^[A-Za-z0-9][A-Za-z0-9._-]*\.svg$/;

export interface GlyphRef {
  id: string;
  /** File name from the manifest. */
  glyph: string;
  /** The manifest's own directory; a drop-in app ships its glyph next to its manifest. */
  dir?: string;
}

/**
 * Reads each app glyph once, at startup, and returns `data:image/svg+xml;base64,…` URLs keyed by app id. The launcher
 * uses them directly as CSS masks, so a tile never waits on (or loses) a request for its glyph while Chromium is
 * starting. A glyph is looked up next to its manifest first, then in `sharedDir`. Rejected or missing names are left
 * out (the tile falls back to /api/glyphs) and logged.
 */
export function loadGlyphs(apps: Iterable<GlyphRef>, sharedDir: string): Map<string, string> {
  const out = new Map<string, string>();
  for (const a of apps) {
    if (!SAFE_SVG.test(a.glyph)) {
      console.error(`vela: glyph ${a.glyph} for ${a.id} rejected: it must be a plain file name ending in .svg`);
      continue;
    }
    const dirs = [...new Set([a.dir, sharedDir].filter((d): d is string => !!d))];
    let found = false;
    for (const dir of dirs) {
      try {
        out.set(a.id, `data:image/svg+xml;base64,${fs.readFileSync(path.join(dir, a.glyph)).toString("base64")}`);
        found = true;
        break;
      } catch (e) {
        if ((e as NodeJS.ErrnoException).code !== "ENOENT") console.error(`vela: glyph ${a.glyph} for ${a.id} unreadable in ${dir}: ${(e as Error).message}`);
      }
    }
    if (!found) console.error(`vela: glyph ${a.glyph} for ${a.id} not found in ${dirs.join(" or ")}`);
  }
  return out;
}

/** The cached data URL, or the /api/glyphs path for a glyph that wasn't loaded. */
export function glyphUrl(glyphs: ReadonlyMap<string, string> | undefined, app: { id: string; glyph: string }): string {
  return glyphs?.get(app.id) ?? `/api/glyphs/${app.glyph}`;
}
