/**
 * A CSS `url("…")` value. Glyphs arrive as `data:image/svg+xml;base64,…` URLs inlined by the
 * daemon, so a mask paints from memory instead of waiting on a request while Chromium starts.
 * Quotes, backslashes and newlines are escaped so no value can end the string early.
 */
export function cssUrl(u: string): string {
  return `url("${u.replace(/[\\"\n\r]/g, (c) => (c === "\n" ? "\\a " : c === "\r" ? "\\d " : `\\${c}`))}")`;
}
