/** Per-title tint, sampled from a tiny (e.g. 32x18) copy of the backdrop. Pure. */

export const NEUTRAL_TINT = "oklch(0.85 0.03 230)";

const L_MIN = 0.78;
const L_MAX = 0.88;
const C_MIN = 0.04;
const C_MAX = 0.16;
/** Pixels below this chroma are treated as gray and don't vote on hue. */
const COLORFUL = 0.03;

const toLinear = (c: number) => {
  const v = c / 255;
  return v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
};

/** sRGB (0-255) to OKLab (Björn Ottosson's matrices). */
function oklab(r8: number, g8: number, b8: number): [number, number, number] {
  const r = toLinear(r8);
  const g = toLinear(g8);
  const b = toLinear(b8);
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
  const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
  const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  return [
    0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
    1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
    0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s,
  ];
}

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));
const round = (v: number, places: number) => Number(v.toFixed(places));

/**
 * Returns "oklch(L C H)". Hue is the chroma-weighted circular mean of pixels with chroma > 0.03
 * (summing the OKLab a/b vectors does exactly that). Lightness and chroma are the chroma-weighted
 * means of those pixels, clamped so the tint always reads as a soft, light glow on the abyss.
 * A grayscale image returns the neutral fallback.
 */
export function tintFromPixels(rgba: Uint8ClampedArray): string {
  let sumA = 0;
  let sumB = 0;
  let sumW = 0;
  let sumL = 0;
  let n = 0;
  for (let i = 0; i + 3 < rgba.length; i += 4) {
    if (rgba[i + 3]! < 128) continue;
    const [L, a, b] = oklab(rgba[i]!, rgba[i + 1]!, rgba[i + 2]!);
    const c = Math.hypot(a, b);
    if (c <= COLORFUL) continue;
    sumA += a; // a = c·cos(h): weighting the unit hue vector by chroma
    sumB += b;
    sumW += c;
    sumL += L * c;
    n++;
  }
  if (sumW === 0) return NEUTRAL_TINT;
  const resultant = Math.hypot(sumA, sumB);
  // Hues that cancel out (e.g. equal teal and orange) have no meaningful mean.
  if (resultant / sumW < 0.05) return NEUTRAL_TINT;
  const h = ((Math.atan2(sumB, sumA) * 180) / Math.PI + 360) % 360;
  const L = clamp(sumL / sumW, L_MIN, L_MAX);
  const C = clamp(sumW / n, C_MIN, C_MAX);
  return `oklch(${round(L, 3)} ${round(C, 3)} ${round(h, 1)})`;
}

