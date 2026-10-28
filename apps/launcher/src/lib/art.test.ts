import { describe, expect, it } from "vitest";
import { cardArt } from "./art.js";

describe("cardArt", () => {
  it("asks Jellyfin for 1280 px card art", () => {
    expect(cardArt("/api/art/jellyfin/abc123ef/Backdrop?w=3840")).toBe("/api/art/jellyfin/abc123ef/Backdrop?w=1280");
  });
  it("leaves other art alone", () => {
    expect(cardArt("/api/art/mock/bd-tos-1.jpg")).toBe("/api/art/mock/bd-tos-1.jpg");
  });
});
