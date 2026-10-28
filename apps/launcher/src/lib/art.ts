import { tintFromPixels } from "./tint.js";

/**
 * Wide cards are 22rem (704 px on a 4K panel, ~750 px when focused). Jellyfin art is proxied
 * at 3840/1280/704 px wide; ask for 1280 so the card stays sharp when it scales up on focus.
 */
export function cardArt(backdropUrl: string): string {
  return backdropUrl.replace(/([?&]w=)3840$/, "$11280");
}

/** Decoded 4K backdrops are ~33 MB each; keep only the most recently used few. */
const CACHE_SIZE = 12;
const decoded = new Map<string, Promise<HTMLImageElement | null>>();

/** URLs currently held, oldest first (for tests). */
export function cachedUrls(): string[] {
  return [...decoded.keys()];
}

/**
 * Loads and decodes an image once, so a crossfade never shows a half-painted frame. Resolves
 * null on failure. An LRU of 12: a hit moves the URL to the newest end; inserting past 12
 * drops the oldest.
 */
export function preload(url: string): Promise<HTMLImageElement | null> {
  let p = decoded.get(url);
  if (p) {
    decoded.delete(url);
    decoded.set(url, p);
  } else {
    const img = new Image();
    img.decoding = "async";
    img.src = url;
    p = img.decode().then(
      () => img,
      () => {
        if (decoded.get(url) === p) decoded.delete(url);
        return null;
      },
    );
    decoded.set(url, p);
    if (decoded.size > CACHE_SIZE) decoded.delete(decoded.keys().next().value!);
  }
  return p;
}

const tints = new Map<string, string>();

/** Tint for a backdrop: drawn to a 32x18 canvas and averaged by tintFromPixels. Cached per URL. */
export async function sampleTint(url: string): Promise<string | null> {
  const known = tints.get(url);
  if (known) return known;
  const img = await preload(url);
  if (!img) return null;
  const canvas = document.createElement("canvas");
  canvas.width = 32;
  canvas.height = 18;
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  if (!ctx) return null;
  ctx.drawImage(img, 0, 0, 32, 18);
  const tint = tintFromPixels(ctx.getImageData(0, 0, 32, 18).data);
  tints.set(url, tint);
  return tint;
}
