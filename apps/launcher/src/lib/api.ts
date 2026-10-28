import { API, type Device, type HomeState, type PerfReport, type VelaEvent } from "@vela/shared";

export type ActionResult = { ok: true } | { ok: false; reason: string };

export interface VelaApi {
  /** Resolves once the daemon answers; retries quietly every retryMs until then. */
  home(): Promise<HomeState>;
  launch(id: string): Promise<ActionResult>;
  play(key: string): Promise<ActionResult>;
  goHome(): Promise<void>;
  devices(): Promise<Device[]>;
  /** Throws an Error carrying the daemon's message when the command fails. */
  command(id: string, cmd: string): Promise<void>;
  postPerf(p: PerfReport): Promise<void>;
  /** Stops reconnecting and closes the event socket. */
  close(): void;
}

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/**
 * Talks to the daemon at `base` (e.g. "http://127.0.0.1:7300"). Every POST sends
 * Content-Type: application/json and a JSON body (`{}` when empty) — the daemon's guard requires both.
 */
export function connect(base: string, onEvent: (e: VelaEvent) => void, opts: { retryMs?: number } = {}): VelaApi {
  const retryMs = opts.retryMs ?? 1000;
  const root = base.replace(/\/+$/, "");
  let closed = false;
  let socket: WebSocket | undefined;

  const openSocket = () => {
    if (closed) return;
    const ws = new WebSocket(root.replace(/^http/, "ws") + API.events);
    socket = ws;
    ws.onmessage = (m: MessageEvent) => {
      let e: VelaEvent;
      try {
        e = JSON.parse(String(m.data)) as VelaEvent;
      } catch {
        return;
      }
      onEvent(e);
    };
    // onclose also follows onerror, so reconnecting there covers both.
    ws.onclose = () => {
      if (socket === ws && !closed) setTimeout(openSocket, retryMs);
    };
  };
  openSocket();

  const post = (path: string, body: unknown = {}) =>
    fetch(root + path, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });

  const action = async (path: string, body: unknown): Promise<ActionResult> => {
    const r = await post(path, body);
    if (r.ok) return { ok: true };
    const data = (await r.json().catch(() => ({}))) as { reason?: string; error?: string };
    return { ok: false, reason: data.reason ?? data.error ?? `http ${r.status}` };
  };

  return {
    async home() {
      for (;;) {
        try {
          const r = await fetch(root + API.home);
          if (r.ok) return (await r.json()) as HomeState;
        } catch {
          // daemon not up yet; retry quietly
        }
        await sleep(retryMs);
      }
    },
    launch: (id) => action(API.launch, { id }),
    play: (key) => action(API.play, { key }),
    async goHome() {
      await post(API.goHome);
    },
    async devices() {
      const r = await fetch(root + API.devices);
      if (!r.ok) throw new Error("Devices aren't available right now. Try again in a moment.");
      return (await r.json()) as Device[];
    },
    async command(id, cmd) {
      const path = API.deviceCommand.replace(":id", encodeURIComponent(id)).replace(":command", encodeURIComponent(cmd));
      const r = await post(path);
      if (r.ok) return;
      const data = (await r.json().catch(() => ({}))) as { error?: string };
      throw new Error(data.error ?? `That didn't work (error ${r.status}). Try again.`);
    },
    async postPerf(p) {
      await post(API.perf, p);
    },
    close() {
      closed = true;
      socket?.close();
    },
  };
}
