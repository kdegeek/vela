import { spawn as nodeSpawn } from "node:child_process";
import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import type { Duplex } from "node:stream";

export interface MpvSession {
  /** `fromTime` is true only when this update carried a real time-pos value (not just a pause change). */
  onTime(cb: (sec: number, paused: boolean, fromTime: boolean) => void): void;
  /** `ipcFailed` is true when mpv never accepted the IPC connection, or the file failed to load before any playback. */
  onExit(cb: (lastSec: number, ipcFailed: boolean) => void): void;
  quit(): void;
}

export interface MpvProc {
  onExit(cb: () => void): void;
  kill(): void;
}

export interface MpvOpts {
  spawn?: (cmd: string, args: string[]) => MpvProc;
  connect?: (sock: string) => Duplex;
}

const CONNECT_RETRIES = 50;
const CONNECT_DELAY_MS = 100;
const DRAIN_MS = 500;

function defaultSpawn(cmd: string, args: string[]): MpvProc {
  const p = nodeSpawn(cmd, args, { stdio: "ignore" });
  return {
    onExit(cb) {
      let done = false;
      const once = () => void (!done && ((done = true), cb()));
      p.on("exit", once);
      p.on("error", (e) => {
        console.error(`vela: failed to spawn ${cmd}: ${e.message}`);
        once();
      });
    },
    kill: () => void p.kill("SIGTERM"),
  };
}

const defaultConnect = (sock: string): Duplex => net.connect(sock);

/** Starts mpv fullscreen on `url` and follows its time-pos and pause properties over the JSON IPC socket. */
export function startMpv(url: string, startSec: number, opts: MpvOpts = {}): MpvSession {
  const spawn = opts.spawn ?? defaultSpawn;
  const connect = opts.connect ?? defaultConnect;
  const sock = path.join(process.env.XDG_RUNTIME_DIR ?? os.tmpdir(), `vela-mpv-${process.pid}-${Math.random().toString(36).slice(2, 8)}.sock`);
  const timeCbs: Array<(sec: number, paused: boolean, fromTime: boolean) => void> = [];
  const exitCbs: Array<(lastSec: number, ipcFailed: boolean) => void> = [];
  let lastSec = startSec;
  let paused = false;
  let exited = false;
  let ipcFailed = false;
  let sawTime = false;
  let stream: Duplex | null = null;
  let retryTimer: ReturnType<typeof setTimeout> | undefined;

  const proc = spawn("mpv", ["--config-dir=/usr/share/vela/mpv", "--fs", "--hwdec=vaapi", "--idle=once", "--force-window=yes", `--input-ipc-server=${sock}`]);

  const send = (command: unknown[]): void => {
    try {
      stream?.write(JSON.stringify({ command }) + "\n");
    } catch {
      /* socket already gone */
    }
  };

  const onLine = (line: string): void => {
    let m: { event?: string; name?: string; data?: unknown };
    try {
      m = JSON.parse(line);
    } catch {
      return;
    }
    if (m.event === "end-file") {
      // --idle=once should quit mpv already; this is the backstop, and flags a file that failed to load.
      const reason = (m as { reason?: string }).reason;
      if (reason === "error" && !sawTime) ipcFailed = true;
      if (reason === "eof" || reason === "error" || reason === "quit") send(["quit"]);
      return;
    }
    if (m.event !== "property-change") return;
    if (m.name === "time-pos" && typeof m.data === "number") {
      lastSec = m.data;
      sawTime = true;
      timeCbs.forEach((cb) => cb(lastSec, paused, true));
    } else if (m.name === "pause" && typeof m.data === "boolean") {
      paused = m.data;
      timeCbs.forEach((cb) => cb(lastSec, paused, false));
    }
  };

  const attach = (attempt: number): void => {
    if (exited) return;
    const s = connect(sock);
    s.setEncoding("utf8");
    let buf = "";
    let connected = false;
    s.on("connect", () => (connected = true));
    s.on("data", (chunk: string) => {
      connected = true;
      buf += chunk.toString();
      let i: number;
      while ((i = buf.indexOf("\n")) >= 0) {
        const line = buf.slice(0, i).trim();
        buf = buf.slice(i + 1);
        if (line) onLine(line);
      }
    });
    s.on("error", () => {
      if (connected || exited) return;
      if (attempt < CONNECT_RETRIES) retryTimer = setTimeout(() => attach(attempt + 1), CONNECT_DELAY_MS);
      else {
        ipcFailed = true;
        proc.kill();
      }
    });
    stream = s;
    send(["observe_property", 1, "time-pos"]);
    send(["observe_property", 2, "pause"]);
    // The URL (it carries the api_key) goes over IPC, never argv. mpv >= 0.38: loadfile <url> <flags> <index> <options>.
    send(["loadfile", url, "replace", -1, `start=${startSec}`]);
  };
  attach(0);

  proc.onExit(() => {
    if (exited) return;
    exited = true;
    clearTimeout(retryTimer);
    let finished = false;
    const finish = (): void => {
      if (finished) return;
      finished = true;
      fs.rmSync(sock, { force: true });
      exitCbs.forEach((cb) => cb(lastSec, ipcFailed));
    };
    // Let buffered time-pos lines arrive before reporting the final position.
    if (!stream || stream.destroyed) return finish();
    stream.once("close", finish);
    setTimeout(finish, DRAIN_MS).unref();
    stream.end();
  });

  return {
    onTime: (cb) => void timeCbs.push(cb),
    onExit: (cb) => void exitCbs.push(cb),
    quit() {
      if (exited) return;
      send(["quit"]);
      setTimeout(() => !exited && proc.kill(), 2000).unref();
    },
  };
}
