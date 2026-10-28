import { describe, expect, it, vi } from "vitest";
import type { VelaEvent } from "@vela/shared";
import type { JellyfinClient } from "../media/jellyfin.js";
import { playJellyfin, START_FAILED } from "./jellyfinReporter.js";
import type { MpvSession } from "./mpv.js";

function setup(positionTicks?: number) {
  let timeCb: (s: number, p: boolean) => void = () => {};
  let exitCb: (l: number) => void = () => {};
  const session: MpvSession = { onTime: (cb) => (timeCb = cb), onExit: (cb) => (exitCb = cb), quit: vi.fn() };
  const start = vi.fn((_u: string, _s: number) => session);
  const jf = {
    resume: vi.fn(async () => [{ id: "i1", positionTicks }]),
    streamUrl: (id: string) => `http://jf/${id}`,
    reportStart: vi.fn(async () => {}),
    reportProgress: vi.fn(async () => {}),
    reportStopped: vi.fn(async () => {}),
  };
  let t = 0;
  const goHome = vi.fn(async () => {});
  const events: VelaEvent[] = [];
  const done = playJellyfin("i1", { jf: jf as unknown as JellyfinClient, start: start as never, goHome, now: () => t, emit: (e) => events.push(e) });
  const ready = () => new Promise<void>((r) => setImmediate(r));
  return { jf, start, goHome, events, done, ready, time: (sec: number, p = false) => ((t = sec * 1000), timeCb(sec, p)), exit: (l: number) => exitCb(l), setNow: (ms: number) => (t = ms) };
}

describe("playJellyfin", () => {
  it("starts at resume position", async () => {
    const s = setup(3_000_000_000);
    await s.ready();
    expect(s.start).toHaveBeenCalledWith("http://jf/i1", 300);
    expect(s.jf.reportStart).toHaveBeenCalledWith("i1", 3_000_000_000);
    s.exit(300);
    await s.done;
  });

  it("reports progress at most every 10 s", async () => {
    const s = setup();
    await s.ready();
    for (let i = 1; i <= 25; i++) s.time(i);
    expect(s.jf.reportProgress).toHaveBeenCalledTimes(2);
    s.exit(25);
    await s.done;
  });

  it("pause change reports immediately", async () => {
    const s = setup();
    await s.ready();
    s.time(1);
    s.time(2, true);
    expect(s.jf.reportProgress).toHaveBeenCalledWith("i1", 20_000_000, true);
    s.exit(2);
    await s.done;
  });

  it("exit reports stopped with last position and goes home", async () => {
    const s = setup();
    await s.ready();
    s.time(5);
    s.setNow(60_000);
    s.exit(42);
    await s.done;
    expect(s.jf.reportStopped).toHaveBeenCalledWith("i1", 420_000_000);
    expect(s.goHome).toHaveBeenCalledTimes(1);
    expect(s.events).toEqual([]);
  });

  it("toasts when mpv exits quickly without playing", async () => {
    const s = setup();
    await s.ready();
    s.setNow(500);
    s.exit(0);
    await s.done;
    expect(s.events).toEqual([{ type: "toast", message: START_FAILED }]);
    expect(s.goHome).toHaveBeenCalled();
  });
});
