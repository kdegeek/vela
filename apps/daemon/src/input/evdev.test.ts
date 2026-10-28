import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { guideHandler, parseEvents, watchGamepads, type InputEvent } from "./evdev.js";

function raw(sec: number, usec: number, type: number, code: number, value: number): Buffer {
  const b = Buffer.alloc(24);
  b.writeBigUInt64LE(BigInt(sec), 0);
  b.writeBigUInt64LE(BigInt(usec), 8);
  b.writeUInt16LE(type, 16);
  b.writeUInt16LE(code, 18);
  b.writeInt32LE(value, 20);
  return b;
}
const ev = (value: number): InputEvent => ({ sec: 0, usec: 0, type: 1, code: 0x13c, value });

function setup(passthrough: boolean) {
  const goHome = vi.fn();
  const h = guideHandler({ goHome, passthrough: () => passthrough });
  return { goHome, h };
}

describe("parseEvents", () => {
  it("parses two packed events", () => {
    const out = parseEvents(Buffer.concat([raw(5, 7, 1, 0x13c, 1), raw(6, 8, 1, 0x13c, -1)]));
    expect(out).toEqual([
      { sec: 5, usec: 7, type: 1, code: 0x13c, value: 1 },
      { sec: 6, usec: 8, type: 1, code: 0x13c, value: -1 },
    ]);
  });
  it("ignores a trailing partial event", () => {
    expect(parseEvents(Buffer.concat([raw(1, 2, 1, 3, 1), Buffer.alloc(10)]))).toHaveLength(1);
  });
});

describe("guideHandler", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("short press goes home", () => {
    const { goHome, h } = setup(false);
    h(ev(1));
    vi.advanceTimersByTime(200);
    h(ev(0));
    expect(goHome).toHaveBeenCalledTimes(1);
  });
  it("short press with passthrough does nothing", () => {
    const { goHome, h } = setup(true);
    h(ev(1));
    vi.advanceTimersByTime(200);
    h(ev(0));
    vi.advanceTimersByTime(2000);
    expect(goHome).not.toHaveBeenCalled();
  });
  it("long press fires at 600 ms while still held, even with passthrough", () => {
    const { goHome, h } = setup(true);
    h(ev(1));
    vi.advanceTimersByTime(599);
    expect(goHome).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(goHome).toHaveBeenCalledTimes(1);
  });
  it("release after long press does not fire twice", () => {
    const { goHome, h } = setup(false);
    h(ev(1));
    vi.advanceTimersByTime(900);
    h(ev(0));
    vi.advanceTimersByTime(2000);
    expect(goHome).toHaveBeenCalledTimes(1);
  });
  it("release at 599 ms counts as short", () => {
    const { goHome, h } = setup(false);
    h(ev(1));
    vi.advanceTimersByTime(599);
    h(ev(0));
    vi.advanceTimersByTime(2000);
    expect(goHome).toHaveBeenCalledTimes(1);
  });
  it("reset cancels a pending long press", () => {
    const { goHome, h } = setup(true);
    h(ev(1));
    h.reset();
    vi.advanceTimersByTime(2000);
    h(ev(0));
    expect(goHome).not.toHaveBeenCalled();
  });
  it("ignores autorepeat, other buttons and event types", () => {
    const { goHome, h } = setup(false);
    h({ sec: 0, usec: 0, type: 1, code: 0x130, value: 1 });
    h({ sec: 0, usec: 0, type: 3, code: 0x13c, value: 1 });
    h({ sec: 0, usec: 0, type: 1, code: 0x130, value: 0 });
    h(ev(2));
    vi.advanceTimersByTime(2000);
    expect(goHome).not.toHaveBeenCalled();
  });
});

describe("watchGamepads", () => {
  let dir = "";
  afterEach(() => {
    vi.useRealTimers();
    if (dir) fs.rmSync(dir, { recursive: true, force: true });
    dir = "";
  });

  it("does not throw when the directory is missing", () => {
    const err = vi.spyOn(console, "error").mockImplementation(() => {});
    const stop = watchGamepads(() => {}, { dir: "/nonexistent/vela-input", intervalMs: 10 });
    stop();
    err.mockRestore();
  });

  it("reads events from a device, opens it once, and reopens after it disappears", async () => {
    dir = fs.mkdtempSync(path.join(os.tmpdir(), "vela-input-"));
    const dev = path.join(dir, "usb-pad-event-joystick");
    execFileSync("mkfifo", [dev]); // a live device never hits EOF; a FIFO with an open writer behaves the same
    const writer = fs.openSync(dev, "r+");
    fs.writeSync(writer, raw(1, 2, 1, 0x13c, 1));
    const seen: InputEvent[] = [];
    const open = vi.spyOn(fs, "createReadStream");
    const stop = watchGamepads((e) => seen.push(e), { dir, intervalMs: 20 });
    await vi.waitFor(() => expect(seen).toHaveLength(1));
    await new Promise((r) => setTimeout(r, 80)); // several rescans
    expect(open.mock.calls.filter((c) => c[0] === dev)).toHaveLength(1);
    stop();
    fs.closeSync(writer);
    open.mockRestore();
  });

  it("calls onDrop when a device stream drops", async () => {
    dir = fs.mkdtempSync(path.join(os.tmpdir(), "vela-input-"));
    fs.mkdirSync(path.join(dir, "usb-pad-event-joystick"));
    const onDrop = vi.fn();
    const stop = watchGamepads(() => {}, { dir, intervalMs: 20, onDrop });
    await vi.waitFor(() => expect(onDrop).toHaveBeenCalled());
    stop();
  });

  it("removes a device on read error so a rescan can reopen it", async () => {
    dir = fs.mkdtempSync(path.join(os.tmpdir(), "vela-input-"));
    const dev = path.join(dir, "usb-pad-event-joystick");
    fs.mkdirSync(dev); // reading a directory fails with EISDIR
    const open = vi.spyOn(fs, "createReadStream");
    const stop = watchGamepads(() => {}, { dir, intervalMs: 20 });
    await vi.waitFor(() => expect(open.mock.calls.filter((c) => c[0] === dev).length).toBeGreaterThan(1));
    stop();
    open.mockRestore();
  });
});
