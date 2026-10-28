/** Spatial focus for the home rows. Pure: the component measures geometry and applies the result. */

export interface FocusState {
  /** Focused row index. */
  row: number;
  /** Remembered column per row, so returning to a row restores its position. */
  cols: number[];
}

export interface Geometry {
  /** Tile left edges (px, untransformed) per row. */
  rowLefts: number[][];
}

export type Direction = "left" | "right" | "up" | "down";

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

/**
 * Left/right clamp within the row. Up/down move one row (clamped) and pick the tile whose
 * on-screen left (left edge + that row's visible shift) is nearest the current tile's.
 */
export function move(s: FocusState, dir: Direction, g: Geometry, visibleShift: number[]): FocusState {
  const lefts = g.rowLefts[s.row] ?? [];
  const col = s.cols[s.row] ?? 0;
  if (dir === "left" || dir === "right") {
    const next = clamp(col + (dir === "right" ? 1 : -1), 0, Math.max(0, lefts.length - 1));
    if (next === col) return s;
    const cols = [...s.cols];
    cols[s.row] = next;
    return { row: s.row, cols };
  }
  const row = clamp(s.row + (dir === "down" ? 1 : -1), 0, Math.max(0, g.rowLefts.length - 1));
  if (row === s.row) return s;
  const from = (lefts[col] ?? 0) + (visibleShift[s.row] ?? 0);
  const target = g.rowLefts[row] ?? [];
  const shift = visibleShift[row] ?? 0;
  let best = 0;
  let bestDist = Infinity;
  target.forEach((left, i) => {
    const d = Math.abs(left + shift - from);
    if (d < bestDist) {
      bestDist = d;
      best = i;
    }
  });
  const cols = [...s.cols];
  cols[row] = best;
  return { row, cols };
}

/** Translate for a row's track that pins the focused tile to the row's start edge until the row's end is visible. */
export function trackShift(tileLeft: number, trackWidth: number, viewport: number): number {
  return -Math.min(tileLeft, Math.max(0, trackWidth - viewport));
}
