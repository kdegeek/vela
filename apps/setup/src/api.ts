import { SETUP_API, type JellyfinServer, type SetupKey, type SetupProgress } from "@vela/shared";

/** 401: the PIN is missing, wrong, or was replaced after too many wrong tries. Ask for the PIN on the TV again. */
export class PinError extends Error {}

/** 429 with a long Retry-After: this phone is locked out for `seconds`; the TV shows a new PIN meanwhile. */
export class LockedError extends Error {
  constructor(
    message: string,
    public seconds: number,
  ) {
    super(message);
  }
}

/** The box answered with an error; `message` is the box's own words (what happened and what to do). */
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}

/** No answer at all: the listener closed (setup finished), or the phone left the network. */
export class UnreachableError extends Error {}

export interface ClientOptions {
  fetch?: typeof fetch;
  sleep?: (ms: number) => Promise<void>;
  /** Silent retries when the box says it's busy (429, Retry-After: 1) before the error shows. */
  maxBusyRetries?: number;
  timeoutMs?: number;
}

export interface SetupClient {
  state(): Promise<SetupProgress>;
  discover(): Promise<JellyfinServer[]>;
  signIn(url: string, username: string, password: string): Promise<{ server: string }>;
  skipMedia(): Promise<SetupProgress>;
  githubKeys(username: string): Promise<SetupKey[]>;
  setKeys(keys: string[]): Promise<SetupProgress>;
  skipSsh(): Promise<SetupProgress>;
  finish(): Promise<SetupProgress>;
}

export const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/** The PIN from the QR code's fragment (`#pin=482913`), or null. */
export function pinFromHash(hash: string): string | null {
  const pin = new URLSearchParams(hash.replace(/^#/, "")).get("pin");
  return pin && /^\d{6}$/.test(pin) ? pin : null;
}

/**
 * The phone's client for /setup/api. Every call carries `Authorization: Pin <pin>`; POSTs send JSON (`{}` when
 * empty), as the listener's guard requires. Calls run one at a time, so a poll and a tap never race each other into
 * the box's rate limit; when it still says busy, the call waits a second and tries again without telling anyone.
 */
export function createClient(getPin: () => string | null, o: ClientOptions = {}): SetupClient {
  const doFetch = o.fetch ?? ((input, init) => fetch(input, init));
  const wait = o.sleep ?? sleep;
  const maxBusy = o.maxBusyRetries ?? 10;
  const timeoutMs = o.timeoutMs ?? 30_000;
  let queue: Promise<unknown> = Promise.resolve();

  async function attempt<T>(method: "GET" | "POST", path: string, body?: unknown): Promise<T> {
    for (let busy = 0; ; busy++) {
      const pin = getPin();
      if (!pin) throw new PinError("Enter the PIN shown on the TV.");
      let r: Response;
      try {
        r = await doFetch(path, {
          method,
          headers: { authorization: `Pin ${pin}`, ...(method === "POST" ? { "content-type": "application/json" } : {}) },
          body: method === "POST" ? JSON.stringify(body ?? {}) : undefined,
          signal: AbortSignal.timeout(timeoutMs),
          cache: "no-store",
        });
      } catch {
        throw new UnreachableError("Your phone can't reach the box. Check that it's on the same Wi-Fi as the box, then try again.");
      }
      if (r.ok) return (await r.json()) as T;
      const error = ((await r.json().catch(() => ({}))) as { error?: unknown }).error;
      const said = typeof error === "string" && error ? error : undefined;
      if (r.status === 429) {
        const after = Number(r.headers.get("retry-after") ?? "1");
        if (Number.isFinite(after) && after > 1) throw new LockedError(said ?? `Too many wrong PINs from this phone. Try again in ${after} seconds.`, Math.ceil(after));
        if (busy < maxBusy) {
          await wait(1000);
          continue;
        }
        throw new ApiError(429, said ?? "The box is busy. Try again in a moment.");
      }
      if (r.status === 401) throw new PinError(said ?? "That PIN is wrong. Check the PIN on the TV.");
      throw new ApiError(r.status, said ?? `The box couldn't do that (error ${r.status}). Try again.`);
    }
  }

  function send<T>(method: "GET" | "POST", path: string, body?: unknown): Promise<T> {
    const run = queue.then(() => attempt<T>(method, path, body));
    queue = run.catch(() => {});
    return run;
  }

  return {
    state: () => send<SetupProgress>("GET", SETUP_API.state),
    discover: async () => (await send<{ servers: JellyfinServer[] }>("GET", SETUP_API.jellyfinDiscover)).servers,
    signIn: (url, username, password) => send<{ server: string }>("POST", SETUP_API.jellyfin, { url, username, password }),
    skipMedia: () => send<SetupProgress>("POST", SETUP_API.jellyfinSkip),
    githubKeys: async (username) => (await send<{ keys: SetupKey[] }>("POST", SETUP_API.sshGithub, { username })).keys,
    setKeys: (keys) => send<SetupProgress>("POST", SETUP_API.sshKeys, { keys }),
    skipSsh: () => send<SetupProgress>("POST", SETUP_API.sshSkip),
    finish: () => send<SetupProgress>("POST", SETUP_API.finish),
  };
}

/**
 * Counts a lockout down to zero: onTick(seconds left) now and then once a second, onDone at zero. Returns a stop
 * function. Follows the clock rather than counting ticks, so a phone that slept shows the right time on waking.
 */
export function countdown(seconds: number, onTick: (left: number) => void, onDone: () => void, now: () => number = Date.now): () => void {
  const end = now() + seconds * 1000;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const tick = () => {
    const left = Math.max(0, Math.ceil((end - now()) / 1000));
    if (left === 0) return onDone();
    onTick(left);
    // Wake on the next whole second before the end, so the number changes on time.
    timer = setTimeout(tick, (end - now()) % 1000 || 1000);
  };
  tick();
  return () => clearTimeout(timer);
}

export type CommitResult =
  /** The listener closed: root installed the settings and setup mode ended (almost certainly). */
  | { kind: "gone" }
  /** Still "committing" after the limit: root most likely refused the files. */
  | { kind: "stuck" }
  /** Someone changed an answer meanwhile, so the box is no longer committing. */
  | { kind: "reopened"; progress: SetupProgress };

/** After Finish: polls the state once a second until the listener goes away, the step changes, or `limitMs` passes. */
export async function waitForCommit(client: SetupClient, o: { sleep?: (ms: number) => Promise<void>; now?: () => number; limitMs?: number } = {}): Promise<CommitResult> {
  const wait = o.sleep ?? sleep;
  const now = o.now ?? Date.now;
  const start = now();
  for (;;) {
    await wait(1000);
    try {
      const p = await client.state();
      if (p.step !== "committing") return { kind: "reopened", progress: p };
    } catch (e) {
      if (e instanceof UnreachableError) return { kind: "gone" };
      throw e;
    }
    if (now() - start >= (o.limitMs ?? 20_000)) return { kind: "stuck" };
  }
}
