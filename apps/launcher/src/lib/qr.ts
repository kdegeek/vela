import qrcode from "qrcode-generator";

export interface Qr {
  /** Modules per side, without a quiet zone (the tile around the SVG provides it). */
  size: number;
  /** One SVG path `d` covering every dark module, in module units: draw it in a `0 0 size size` viewBox. */
  path: string;
  dark: (row: number, col: number) => boolean;
}

/**
 * Encodes `text` as a QR code (byte mode, error correction M, smallest version that fits). The path merges each row's
 * runs of dark modules into one rectangle, so the code stays a handful of shapes and edges land on whole modules.
 */
export function encodeQr(text: string): Qr {
  const qr = qrcode(0, "M");
  qr.addData(text, "Byte");
  qr.make();
  const size = qr.getModuleCount();
  let path = "";
  for (let r = 0; r < size; r++) {
    for (let c = 0; c < size; ) {
      if (!qr.isDark(r, c)) {
        c++;
        continue;
      }
      const start = c;
      while (c < size && qr.isDark(r, c)) c++;
      path += `M${start} ${r}h${c - start}v1h${start - c}z`;
    }
  }
  return { size, path, dark: (r, c) => qr.isDark(r, c) };
}
