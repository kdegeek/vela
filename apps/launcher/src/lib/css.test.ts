import { describe, expect, it } from "vitest";
import { cssUrl } from "./css.js";

describe("cssUrl", () => {
  it("passes a base64 SVG data URL through unchanged", () => {
    const u = "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciLz4=";
    expect(cssUrl(u)).toBe(`url("${u}")`);
  });

  it("keeps the /api/glyphs fallback path", () => {
    expect(cssUrl("/api/glyphs/home.svg")).toBe('url("/api/glyphs/home.svg")');
  });

  it("escapes characters that would end the string", () => {
    expect(cssUrl('a"b\\c\nd')).toBe('url("a\\"b\\\\c\\a d")');
  });
});
