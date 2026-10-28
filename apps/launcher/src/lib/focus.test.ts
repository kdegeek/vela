import { describe, expect, it } from "vitest";
import { move, trackShift, type FocusState, type Geometry } from "./focus.js";

const g: Geometry = { rowLefts: [[0, 240, 480], [0, 370, 740]] };

describe("focus", () => {
  it("right clamps at row end", () => {
    const s: FocusState = { row: 0, cols: [2, 0] };
    expect(move(s, "right", g, [0, 0])).toEqual({ row: 0, cols: [2, 0] });
    expect(move({ row: 0, cols: [1, 0] }, "right", g, [0, 0])).toEqual({ row: 0, cols: [2, 0] });
    expect(move({ row: 0, cols: [0, 0] }, "left", g, [0, 0])).toEqual({ row: 0, cols: [0, 0] });
  });

  it("down picks nearest on-screen tile", () => {
    expect(move({ row: 0, cols: [1, 0] }, "down", g, [0, 0])).toEqual({ row: 1, cols: [1, 1] });
  });

  it("down accounts for each row's visible shift", () => {
    // Row 0 is shifted so col 2 sits at 0 on screen; row 1 is unshifted, so col 0 is nearest.
    expect(move({ row: 0, cols: [2, 2] }, "down", g, [-480, 0])).toEqual({ row: 1, cols: [2, 0] });
  });

  it("up from top row stays", () => {
    const s: FocusState = { row: 0, cols: [1, 2] };
    expect(move(s, "up", g, [0, 0])).toEqual(s);
    expect(move({ row: 1, cols: [1, 2] }, "down", g, [0, 0])).toEqual({ row: 1, cols: [1, 2] });
  });

  it("trackShift pins to left until the row end", () => {
    // Track 2000 px wide in a 1000 px viewport: the shift can go at most to -1000.
    expect(trackShift(0, 2000, 1000)).toBe(-0);
    expect(trackShift(600, 2000, 1000)).toBe(-600);
    expect(trackShift(1500, 2000, 1000)).toBe(-1000);
    // A row that fits never moves.
    expect(trackShift(500, 800, 1000)).toBe(-0);
  });
});
