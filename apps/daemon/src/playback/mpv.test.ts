import { PassThrough } from "node:stream";
import { describe, expect, it, vi } from "vitest";
import { startMpv, type MpvProc } from "./mpv.js";

function fake() {
  const stream = new PassThrough();
  const written: string[] = [];
  stream.on("data", (c) => written.push(String(c)));
  let exit: () => void = () => {};
  const spawn = vi.fn((_c: string, _a: string[]): MpvProc => ({ onExit: (cb) => (exit = cb), kill: vi.fn() }));
  return { stream, written, spawn, connect: () => stream, exit: () => exit() };
}

describe("startMpv", () => {
  it("passes the documented args and observes properties", () => {
    const f = fake();
    startMpv("http://jf/v", 300, { spawn: f.spawn, connect: f.connect });
    const [cmd, args] = f.spawn.mock.calls[0]!;
    expect(cmd).toBe("mpv");
    expect(args).toEqual(expect.arrayContaining(["--config-dir=/usr/share/vela/mpv", "--fs", "--hwdec=vaapi", "--start=300", "http://jf/v"]));
    expect(args.some((a) => a.startsWith("--input-ipc-server="))).toBe(true);
    expect(f.written.join("")).toContain('"observe_property",1,"time-pos"');
    expect(f.written.join("")).toContain('"observe_property",2,"pause"');
  });

  it("reports time, pause and the last position on exit", () => {
    const f = fake();
    const s = startMpv("u", 0, { spawn: f.spawn, connect: f.connect });
    const times: Array<[number, boolean]> = [];
    let last = -1;
    s.onTime((t, p) => times.push([t, p]));
    s.onExit((l) => (last = l));
    f.stream.write('{"event":"property-change","name":"time-pos","data":12.5}\n{"event":"property-change","name":"pause","data":true}\n');
    expect(times).toEqual([[12.5, false], [12.5, true]]);
    f.exit();
    expect(last).toBe(12.5);
  });
});
