import { describe, expect, it, vi } from "vitest";
import type { VelaEvent } from "@vela/shared";
import type { JellyfinClient } from "../media/jellyfin.js";
import { createPlayer, playJellyfin, START_FAILED } from "./jellyfinReporter.js";
import type { MpvSession } from "./mpv.js";

function setup(positionTicks?: number) {
  let timeCb: (s: number, p: boolean, f: boolean) => void = () => {};
  let exitCb: (l: number, f: boolean) => void = () => {};
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
  return { jf, start, goHome, events, done, ready, time: (sec: number, p = false) => ((t = sec * 1000), timeCb(sec, p, true)), pauseOnly: (sec: number) => timeCb(sec, false, false), exit: (l: number, failed = false) => exitCb(l, failed), setNow: (ms: number) => (t = ms) };
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

  it("pause-only event then exit at 500 ms still toasts", async () => {
    const s = setup();
    await s.ready();
    s.pauseOnly(0);
    s.setNow(500);
    s.exit(0);
    await s.done;
    expect(s.events).toEqual([{ type: "toast", message: START_FAILED }]);
  });

  it("toasts and goes home when IPC never connected, even after 2 s", async () => {
    const s = setup();
    await s.ready();
    s.setNow(5500);
    s.exit(0, true);
    await s.done;
    expect(s.events).toEqual([{ type: "toast", message: START_FAILED }]);
    expect(s.goHome).toHaveBeenCalled();
  });
});

describe("createPlayer", () => {
  it("two rapid plays start one mpv after the other, never both", async () => {
    let active = 0;
    let maxActive = 0;
    const exits: Array<(l: number, f: boolean) => void> = [];
    const start = vi.fn(() => {
      active++;
      maxActive = Math.max(maxActive, active);
      let exit: (l: number, f: boolean) => void = () => {};
      const session: MpvSession = {
        onTime: () => {},
        onExit: (cb) => (exit = (l, f) => (active--, cb(l, f))),
        quit: () => queueMicrotask(() => exit(1, false)),
      };
      exits.push((l, f) => exit(l, f));
      return session;
    });
    const jf = { resume: async () => [], streamUrl: (id: string) => `u/${id}`, reportStart: async () => {}, reportProgress: async () => {}, reportStopped: async () => {} };
    const expectPlayer = vi.fn();
    const play = createPlayer({ jf: jf as unknown as JellyfinClient, start: start as never, goHome: async () => {}, runner: { expectPlayer } });
    await Promise.all([play("a"), play("b")]);
    for (let i = 0; i < 20; i++) await new Promise<void>((r) => setImmediate(r));
    expect(start).toHaveBeenCalledTimes(2);
    expect(start.mock.calls.map((c) => (c as unknown as string[])[0])).toEqual(["u/a", "u/b"]);
    expect(maxActive).toBe(1);
  });
});
