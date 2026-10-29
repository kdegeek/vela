import { describe, expect, it } from "vitest";
import type { PerfReport } from "@vela/shared";
import { decideEffects, summarize } from "./perf.js";

const report = (p95Ms: number): PerfReport => ({ frames: 1800, p50Ms: 16.7, p95Ms, droppedPct: 0, effects: "full", width: 3840, height: 2160 });

describe("summarize", () => {
  it("summarize of 60×16.7 ms gives p95 ≈ 16.7, dropped 0", () => {
    const s = summarize(Array.from({ length: 60 }, () => 16.7));
    expect(s.frames).toBe(60);
    expect(s.p50Ms).toBeCloseTo(16.7, 5);
    expect(s.p95Ms).toBeCloseTo(16.7, 5);
    expect(s.droppedPct).toBe(0);
  });

  it("three 40 ms frames in 100 count as 3% dropped", () => {
    const s = summarize([...Array.from({ length: 97 }, () => 16.7), 40, 40, 40]);
    expect(s.frames).toBe(100);
    expect(s.droppedPct).toBeCloseTo(3, 5);
    expect(s.p50Ms).toBeCloseTo(16.7, 5);
  });

  it("an empty sample reports zero frames rather than NaN", () => {
    expect(summarize([])).toEqual({ frames: 0, p50Ms: 0, p95Ms: 0, droppedPct: 0 });
  });
});

describe("decideEffects", () => {
  it("auto with p95 25 → lite", () => {
    expect(decideEffects("auto", report(25))).toBe("lite");
  });

  it("auto with a smooth run, or no run yet, stays full", () => {
    expect(decideEffects("auto", report(17))).toBe("full");
    expect(decideEffects("auto", null)).toBe("full");
  });

  it("full stays full regardless", () => {
    expect(decideEffects("full", report(80))).toBe("full");
    expect(decideEffects("full", null)).toBe("full");
  });

  it("lite stays lite regardless", () => {
    expect(decideEffects("lite", report(16.7))).toBe("lite");
  });
});
