import { describe, expect, it } from "vitest";
import { formatClock } from "./clock.js";

describe("formatClock", () => {
  const evening = new Date(2026, 9, 28, 17, 5);
  it("honours clock24h", () => {
    expect(formatClock(evening, true, "en-US")).toBe("17:05");
  });
  it("uses a 12-hour clock otherwise", () => {
    expect(formatClock(evening, false, "en-US")).toMatch(/^5:05\sPM$/);
  });
});
