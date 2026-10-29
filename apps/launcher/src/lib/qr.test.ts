import jsQR from "jsqr";
import { describe, expect, it } from "vitest";
import { encodeQr, type Qr } from "./qr.js";

/** Paints the code the way the TV does (dark modules on a light tile, 4-module quiet zone) and decodes the pixels. */
function decode(qr: Qr, scale = 6): string | undefined {
  const quiet = 4;
  const side = (qr.size + 2 * quiet) * scale;
  const px = new Uint8ClampedArray(side * side * 4).fill(255);
  for (let r = 0; r < qr.size; r++) {
    for (let c = 0; c < qr.size; c++) {
      if (!qr.dark(r, c)) continue;
      for (let y = 0; y < scale; y++) {
        for (let x = 0; x < scale; x++) {
          const i = (((r + quiet) * scale + y) * side + (c + quiet) * scale + x) * 4;
          px[i] = px[i + 1] = px[i + 2] = 0;
        }
      }
    }
  }
  return jsQR(px, side, side)?.data;
}

/** Paints the SVG path itself (not the encoder's matrix), so a wrong run length or offset fails the decode. */
function decodePath(qr: Qr, scale = 6): string | undefined {
  const quiet = 4;
  const side = (qr.size + 2 * quiet) * scale;
  const px = new Uint8ClampedArray(side * side * 4).fill(255);
  for (const m of qr.path.matchAll(/M(\d+) (\d+)h(\d+)v1h-(\d+)z/g)) {
    const [x0, y0, w, back] = [m[1], m[2], m[3], m[4]].map(Number) as [number, number, number, number];
    expect(back).toBe(w);
    for (let y = (y0 + quiet) * scale; y < (y0 + quiet + 1) * scale; y++) {
      for (let x = (x0 + quiet) * scale; x < (x0 + quiet + w) * scale; x++) {
        const i = (y * side + x) * 4;
        px[i] = px[i + 1] = px[i + 2] = 0;
      }
    }
  }
  return jsQR(px, side, side)?.data;
}

describe("setup QR code", () => {
  it("scans back to the setup address with the PIN in the fragment", () => {
    const text = "http://192.168.1.42:7301/#pin=482913";
    const qr = encodeQr(text);
    expect(decode(qr)).toBe(text);
    expect(decodePath(qr)).toBe(text);
  });

  it("stays a small, sharp code for the longest address the TV can show", () => {
    const text = "http://255.255.255.255:65535/#pin=000000";
    const qr = encodeQr(text);
    // Version 3 (29 modules) at error correction M: about 33 px per module on a 4K tile.
    expect(qr.size).toBeLessThanOrEqual(29);
    expect(decodePath(qr)).toBe(text);
  });

  it("draws only whole-module rectangles", () => {
    const qr = encodeQr("http://10.0.0.7:7301/#pin=123456");
    expect(qr.path).toMatch(/^(M\d+ \d+h\d+v1h-\d+z)+$/);
  });
});
