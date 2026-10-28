import { describe, expect, it } from "vitest";
import { tintFromPixels } from "./tint.js";

function fill(r: number, g: number, b: number, n = 32 * 18): Uint8ClampedArray {
  const px = new Uint8ClampedArray(n * 4);
  for (let i = 0; i < n; i++) px.set([r, g, b, 255], i * 4);
  return px;
}

function parse(s: string): { l: number; c: number; h: number } {
  const m = /^oklch\(([\d.]+) ([\d.]+) ([\d.]+)\)$/.exec(s);
  if (!m) throw new Error(`not an oklch() string: ${s}`);
  return { l: Number(m[1]), c: Number(m[2]), h: Number(m[3]) };
}

describe("tintFromPixels", () => {
  it("pure orange pixels give hue ≈ 50 ±8", () => {
    const { l, c, h } = parse(tintFromPixels(fill(255, 128, 0)));
    expect(Math.abs(h - 50)).toBeLessThanOrEqual(8);
    expect(c).toBeGreaterThanOrEqual(0.04);
    expect(c).toBeLessThanOrEqual(0.16);
    expect(l).toBeLessThanOrEqual(0.88);
  });

  it("gray image returns the neutral fallback", () => {
    expect(tintFromPixels(fill(128, 128, 128))).toBe("oklch(0.85 0.03 230)");
  });

  it("lightness is clamped to ≥ 0.78 for a dark image", () => {
    const { l, h } = parse(tintFromPixels(fill(40, 10, 10)));
    expect(l).toBeGreaterThanOrEqual(0.78);
    expect(Math.abs(h - 23)).toBeLessThanOrEqual(8);
  });

  it("hue is a circular mean, not an arithmetic one", () => {
    // Pink at ~353° and red-orange at ~34°: the mean must land near 13°, not swing to ~193°.
    const px = new Uint8ClampedArray([...fill(255, 0, 160, 10), ...fill(255, 60, 0, 10)]);
    const { h } = parse(tintFromPixels(px));
    expect(h < 60 || h > 330).toBe(true);
  });
});
