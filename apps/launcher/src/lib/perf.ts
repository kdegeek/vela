import type { PerfReport } from "@vela/shared";

/** One frame at 60 Hz. */
const FRAME_MS = 1000 / 60;
/** An interval longer than 1.5 frames means at least one frame was missed. */
const DROPPED_MS = 1.5 * FRAME_MS;
/** Auto mode falls back to lite effects when the 95th-percentile frame is slower than this. */
const LITE_P95_MS = 20;

/** Nearest-rank percentile of an ascending array. */
function percentile(sorted: number[], p: number): number {
  if (sorted.length === 0) return 0;
  const rank = Math.ceil((p / 100) * sorted.length);
  return sorted[Math.min(sorted.length, Math.max(1, rank)) - 1]!;
}

/** Summarizes requestAnimationFrame intervals; droppedPct is 0–100. */
export function summarize(intervalsMs: number[]): Omit<PerfReport, "effects" | "width" | "height"> {
  const frames = intervalsMs.length;
  if (frames === 0) return { frames: 0, p50Ms: 0, p95Ms: 0, droppedPct: 0 };
  const sorted = [...intervalsMs].sort((a, b) => a - b);
  const dropped = intervalsMs.filter((ms) => ms > DROPPED_MS).length;
  return { frames, p50Ms: percentile(sorted, 50), p95Ms: percentile(sorted, 95), droppedPct: (dropped / frames) * 100 };
}

/** Forced modes pass through; auto stays full until a run shows p95 above 20 ms. */
export function decideEffects(cfg: "auto" | "full" | "lite", r: PerfReport | null): "full" | "lite" {
  if (cfg !== "auto") return cfg;
  return r !== null && r.p95Ms > LITE_P95_MS ? "lite" : "full";
}

/** Records requestAnimationFrame intervals for `durationMs`. Browser only. */
export function sampleFrames(durationMs: number): Promise<number[]> {
  return new Promise((resolve) => {
    const out: number[] = [];
    let start = 0;
    let last = 0;
    const tick = (now: number) => {
      if (start === 0) start = now;
      else out.push(now - last);
      last = now;
      if (now - start >= durationMs) resolve(out);
      else requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  });
}
