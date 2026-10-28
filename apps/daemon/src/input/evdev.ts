import fs from "node:fs";
import path from "node:path";

export interface InputEvent {
  sec: number;
  usec: number;
  type: number;
  code: number;
  value: number;
}

const EV_KEY = 1;
const BTN_MODE = 0x13c;
const EVENT_SIZE = 24; // struct input_event on x86_64: u64 sec, u64 usec, u16 type, u16 code, s32 value
const DEFAULT_LONG_PRESS_MS = 600;

export function parseEvents(buf: Buffer): InputEvent[] {
  const out: InputEvent[] = [];
  for (let o = 0; o + EVENT_SIZE <= buf.length; o += EVENT_SIZE) {
    out.push({
      sec: Number(buf.readBigUInt64LE(o)),
      usec: Number(buf.readBigUInt64LE(o + 8)),
      type: buf.readUInt16LE(o + 16),
      code: buf.readUInt16LE(o + 18),
      value: buf.readInt32LE(o + 20),
    });
  }
  return out;
}

export function guideHandler(deps: {
  goHome(): void;
  passthrough(): boolean;
  now(): number;
  longPressMs?: number;
}): (e: InputEvent) => void {
  const longMs = deps.longPressMs ?? DEFAULT_LONG_PRESS_MS;
  let downAt: number | null = null;
  let fired = false;
  return (e) => {
    if (e.type !== EV_KEY || e.code !== BTN_MODE) return;
    if (e.value === 1) {
      downAt = deps.now();
      fired = false;
    } else if (e.value === 2) {
      // key repeat while held: fire as soon as the hold is long enough
      if (downAt !== null && !fired && deps.now() - downAt >= longMs) {
        fired = true;
        deps.goHome();
      }
    } else if (e.value === 0) {
      if (downAt === null) return;
      const held = deps.now() - downAt;
      downAt = null;
      if (fired) return;
      if (held >= longMs || !deps.passthrough()) deps.goHome();
    }
  };
}

export interface WatchOptions {
  dir?: string;
  intervalMs?: number;
}

export function watchGamepads(onEvent: (e: InputEvent) => void, opts: WatchOptions = {}): () => void {
  const dir = opts.dir ?? "/dev/input/by-id";
  const open = new Map<string, fs.ReadStream>();
  let warned = false;
  let stopped = false;

  const scan = (): void => {
    if (stopped) return;
    let names: string[];
    try {
      names = fs.readdirSync(dir).filter((n) => n.endsWith("-event-joystick"));
    } catch (e) {
      if (!warned) {
        warned = true;
        console.error(`vela: gamepad input unavailable (${(e as Error).message})`);
      }
      return;
    }
    for (const name of names) {
      const file = path.join(dir, name);
      if (open.has(file)) continue;
      const stream = fs.createReadStream(file);
      open.set(file, stream);
      let rest: Buffer = Buffer.alloc(0);
      const drop = (): void => {
        if (open.get(file) === stream) open.delete(file);
        stream.destroy();
      };
      stream.on("data", (chunk) => {
        const buf = rest.length ? Buffer.concat([rest, chunk as Buffer]) : (chunk as Buffer);
        const whole = buf.length - (buf.length % EVENT_SIZE);
        rest = buf.subarray(whole);
        for (const ev of parseEvents(buf.subarray(0, whole))) onEvent(ev);
      });
      stream.on("error", drop);
      stream.on("close", drop);
    }
  };

  scan();
  const timer = setInterval(scan, opts.intervalMs ?? 5000);
  timer.unref();
  return () => {
    stopped = true;
    clearInterval(timer);
    for (const s of open.values()) s.destroy();
    open.clear();
  };
}
