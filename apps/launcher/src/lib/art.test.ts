import { describe, expect, it, vi } from "vitest";
import { cardArt } from "./art.js";

describe("cardArt", () => {
  it("asks Jellyfin for 1280 px card art", () => {
    expect(cardArt("/api/art/jellyfin/abc123ef/Backdrop?w=3840")).toBe("/api/art/jellyfin/abc123ef/Backdrop?w=1280");
  });
  it("leaves other art alone", () => {
    expect(cardArt("/api/art/mock/bd-tos-1.jpg")).toBe("/api/art/mock/bd-tos-1.jpg");
  });
});

describe("preload cache", () => {
  it("cache evicts the oldest beyond 12", async () => {
    const { preload, cachedUrls } = await import("./art.js");
    vi.stubGlobal(
      "Image",
      class {
        src = "";
        decoding = "";
        decode() {
          return Promise.resolve();
        }
      },
    );
    for (let i = 0; i < 13; i++) await preload(`/bd-${i}.jpg`);
    expect(cachedUrls()).toHaveLength(12);
    expect(cachedUrls()).not.toContain("/bd-0.jpg");
    expect(cachedUrls()).toContain("/bd-12.jpg");
    // A hit refreshes recency: touching bd-1 makes bd-2 the next to go.
    await preload("/bd-1.jpg");
    await preload("/bd-13.jpg");
    expect(cachedUrls()).toContain("/bd-1.jpg");
    expect(cachedUrls()).not.toContain("/bd-2.jpg");
    vi.unstubAllGlobals();
  });
});
