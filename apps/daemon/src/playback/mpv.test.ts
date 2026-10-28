import fs from "node:fs";
import { PassThrough } from "node:stream";
import { afterEach, describe, expect, it, vi } from "vitest";
import { startMpv, type MpvProc } from "./mpv.js";

function fake() {
  const stream = new PassThrough();
  const written: string[] = [];
  stream.on("data", (c) => written.push(String(c)));
  let exit: () => void = () => {};
  const kill = vi.fn();
  const spawn = vi.fn((_c: string, _a: string[]): MpvProc => ({ onExit: (cb) => (exit = cb), kill }));
  return { stream, written, spawn, kill, connect: () => stream, exit: () => exit() };
}
const tick = () => new Promise<void>((r) => setImmediate(r));

describe("startMpv", () => {
  afterEach(() => vi.useRealTimers());

  it("passes the documented args and observes properties", () => {
    const f = fake();
    startMpv("http://jf/v", 300, { spawn: f.spawn, connect: f.connect });
    const [cmd, args] = f.spawn.mock.calls[0]!;
    expect(cmd).toBe("mpv");
    expect(args).toEqual(expect.arrayContaining(["--config-dir=/usr/share/vela/mpv", "--fs", "--hwdec=vaapi", "--idle=yes", "--force-window=yes"]));
    expect(args.some((a) => a.startsWith("--input-ipc-server="))).toBe(true);
    expect(args.some((a) => a.startsWith("--start"))).toBe(false);
    const out = f.written.join("");
    expect(out).toContain('"observe_property",1,"time-pos"');
    expect(out).toContain('"observe_property",2,"pause"');
  });

  it("argv never contains the stream URL; loadfile goes over IPC", () => {
    const f = fake();
    startMpv("http://jf/v?api_key=secret", 300, { spawn: f.spawn, connect: f.connect });
    const args = f.spawn.mock.calls[0]![1];
    expect(args.some((a) => a.includes("api_key") || a.includes("http"))).toBe(false);
    expect(f.written.join("")).toContain('{"command":["loadfile","http://jf/v?api_key=secret","replace",-1,"start=300"]}');
  });

  it("reports time, pause (without fromTime) and the last position on exit", async () => {
    const f = fake();
    const s = startMpv("u", 7, { spawn: f.spawn, connect: f.connect });
    const times: Array<[number, boolean, boolean]> = [];
    let last = -1;
    s.onTime((t, p, ft) => times.push([t, p, ft]));
    s.onExit((l) => (last = l));
    f.stream.write('{"event":"property-change","name":"pause","data":false}\n{"event":"property-change","name":"time-pos","data":12.5}\n');
    await tick();
    expect(times).toEqual([[7, false, false], [12.5, false, true]]);
    f.exit();
    await tick();
    await tick();
    expect(last).toBe(12.5);
  });

  it("frames a JSON line split across chunks", async () => {
    const f = fake();
    const s = startMpv("u", 0, { spawn: f.spawn, connect: f.connect });
    const got: number[] = [];
    s.onTime((t) => got.push(t));
    f.stream.write('{"event":"property-change","name":"time-p');
    await tick();
    f.stream.write('os","data":3}\n');
    await tick();
    expect(got).toEqual([3]);
  });

  it("removes the socket file on exit", async () => {
    const f = fake();
    const rm = vi.spyOn(fs, "rmSync");
    startMpv("u", 0, { spawn: f.spawn, connect: f.connect }).onExit(() => {});
    f.exit();
    await tick();
    await tick();
    expect(rm).toHaveBeenCalledWith(expect.stringMatching(/vela-mpv-.*\.sock$/), { force: true });
    rm.mockRestore();
  });

  it("kills mpv and reports ipcFailed when IPC never connects", async () => {
    vi.useFakeTimers();
    const f = fake();
    const connect = () => {
      const s = new PassThrough();
      let failed = false;
      s.write = (() => {
        if (!failed) ((failed = true), s.emit("error", new Error("ENOENT"))); // first write comes right after handlers attach
        return false;
      }) as never;
      return s;
    };
    const s = startMpv("u", 0, { spawn: f.spawn, connect });
    let res: [number, boolean] | undefined;
    s.onExit((l, failed) => (res = [l, failed]));
    await vi.advanceTimersByTimeAsync(6000);
    expect(f.kill).toHaveBeenCalled();
    f.exit();
    await vi.advanceTimersByTimeAsync(10);
    expect(res).toEqual([0, true]);
  });
});
