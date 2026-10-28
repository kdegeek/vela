import { describe, expect, it } from "vitest";
import { isMediaKey } from "./api.js";

describe("isMediaKey", () => {
  it("accepts jellyfin and mock keys", () => {
    expect(isMediaKey("jellyfin:abc")).toBe(true);
    expect(isMediaKey("mock:sintel")).toBe(true);
  });
  it("rejects unknown sources and empty ids", () => {
    expect(isMediaKey("plex:1")).toBe(false);
    expect(isMediaKey("jellyfin:")).toBe(false);
  });
});
