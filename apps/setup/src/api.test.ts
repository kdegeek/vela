import { afterEach, describe, expect, it, vi } from "vitest";
import { SETUP_API, type SetupProgress } from "@vela/shared";
import { ApiError, countdown, createClient, LockedError, PinError, pinFromHash, UnreachableError, waitForCommit, type SetupClient } from "./api.js";

const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...headers } });

const progress = (step: SetupProgress["step"]): SetupProgress => ({ step, media: null, keys: [], sshSkipped: false });

/** A fetch that answers from a list, recording each request. */
function scripted(...answers: (Response | Error)[]) {
  const calls: { path: string; init: RequestInit }[] = [];
  const fetch = vi.fn(async (path: string | URL | Request, init?: RequestInit) => {
    calls.push({ path: String(path), init: init ?? {} });
    const a = answers.shift();
    if (!a) throw new Error("no more answers");
    if (a instanceof Error) throw a;
    return a;
  }) as unknown as typeof globalThis.fetch;
  return { fetch, calls };
}

const noSleep = vi.fn(async () => {});

describe("setup API client", () => {
  afterEach(() => {
    vi.useRealTimers();
    noSleep.mockClear();
  });

  it("sends the PIN on every call and JSON on every POST, {} when there is nothing to send", async () => {
    const { fetch, calls } = scripted(json(200, progress("media")), json(200, progress("ssh")), json(200, { server: "Den" }));
    const c = createClient(() => "482913", { fetch, sleep: noSleep });
    await c.state();
    await c.skipMedia();
    await c.signIn("http://10.0.0.5:8096", "vela", "hunter2");
    expect(calls.map((x) => [x.init.method, x.path])).toEqual([
      ["GET", SETUP_API.state],
      ["POST", SETUP_API.jellyfinSkip],
      ["POST", SETUP_API.jellyfin],
    ]);
    for (const { init } of calls) expect((init.headers as Record<string, string>).authorization).toBe("Pin 482913");
    expect(calls[0]!.init.body).toBeUndefined();
    expect((calls[1]!.init.headers as Record<string, string>)["content-type"]).toBe("application/json");
    expect(calls[1]!.init.body).toBe("{}");
    expect(JSON.parse(String(calls[2]!.init.body))).toEqual({ url: "http://10.0.0.5:8096", username: "vela", password: "hunter2" });
    // The password goes in the body only, never the address.
    expect(calls[2]!.path).not.toMatch(/hunter2/);
  });

  it("retries silently, a second apart, while the box is busy", async () => {
    const busy = () => json(429, { error: "The box is busy. Try again in a second." }, { "retry-after": "1" });
    const { fetch, calls } = scripted(busy(), busy(), json(200, progress("ssh")));
    const c = createClient(() => "482913", { fetch, sleep: noSleep });
    expect((await c.state()).step).toBe("ssh");
    expect(calls).toHaveLength(3);
    expect(noSleep.mock.calls).toEqual([[1000], [1000]]);
  });

  it("gives up on busy after the retry cap and shows the box's words", async () => {
    const busy = () => json(429, { error: "The box is busy. Try again in a second." }, { "retry-after": "1" });
    const { fetch } = scripted(busy(), busy(), busy());
    const c = createClient(() => "482913", { fetch, sleep: noSleep, maxBusyRetries: 2 });
    await expect(c.state()).rejects.toEqual(new ApiError(429, "The box is busy. Try again in a second."));
  });

  it("turns a lockout into a countdown with the seconds from Retry-After", async () => {
    const { fetch } = scripted(json(429, { error: "Too many wrong PINs from this device. The TV shows a new one; try again in 30 seconds." }, { "retry-after": "30" }));
    const c = createClient(() => "111111", { fetch, sleep: noSleep });
    const e = await c.state().catch((x: unknown) => x);
    expect(e).toBeInstanceOf(LockedError);
    expect((e as LockedError).seconds).toBe(30);
    expect((e as LockedError).message).toMatch(/new one/);
    expect(noSleep).not.toHaveBeenCalled();
  });

  it("asks for the PIN again on 401, with the box's reason", async () => {
    const { fetch } = scripted(json(401, { error: "That PIN is wrong. Check the PIN on the TV." }));
    const c = createClient(() => "000000", { fetch, sleep: noSleep });
    await expect(c.state()).rejects.toEqual(new PinError("That PIN is wrong. Check the PIN on the TV."));
  });

  it("asks for the PIN without calling the box when there is none", async () => {
    const { fetch, calls } = scripted();
    const c = createClient(() => null, { fetch, sleep: noSleep });
    await expect(c.state()).rejects.toBeInstanceOf(PinError);
    expect(calls).toHaveLength(0);
  });

  it("passes the box's error text through, and says the box is unreachable when nothing answers", async () => {
    const { fetch } = scripted(json(502, { error: "Vela couldn't reach http://10.0.0.9:8096. Check the address and that the server is on." }), new TypeError("Failed to fetch"));
    const c = createClient(() => "482913", { fetch, sleep: noSleep });
    await expect(c.signIn("http://10.0.0.9:8096", "a", "b")).rejects.toEqual(new ApiError(502, "Vela couldn't reach http://10.0.0.9:8096. Check the address and that the server is on."));
    await expect(c.state()).rejects.toBeInstanceOf(UnreachableError);
  });

  it("runs one call at a time", async () => {
    let inFlight = 0;
    let most = 0;
    const fetch = vi.fn(async () => {
      most = Math.max(most, ++inFlight);
      await new Promise((r) => setTimeout(r, 5));
      inFlight--;
      return json(200, progress("media"));
    }) as unknown as typeof globalThis.fetch;
    const c = createClient(() => "482913", { fetch, sleep: noSleep });
    await Promise.all([c.state(), c.state(), c.skipMedia()]);
    expect(most).toBe(1);
  });

  it("reads the PIN from the QR code's fragment", () => {
    expect(pinFromHash("#pin=482913")).toBe("482913");
    expect(pinFromHash("pin=007007")).toBe("007007");
    expect(pinFromHash("#pin=48291")).toBeNull();
    expect(pinFromHash("#pin=48291x")).toBeNull();
    expect(pinFromHash("")).toBeNull();
  });
});

describe("lockout countdown", () => {
  afterEach(() => vi.useRealTimers());

  it("ticks once a second and finishes at zero", () => {
    vi.useFakeTimers();
    const ticks: number[] = [];
    const done = vi.fn();
    countdown(3, (s) => ticks.push(s), done);
    expect(ticks).toEqual([3]);
    vi.advanceTimersByTime(1000);
    vi.advanceTimersByTime(1000);
    expect(ticks).toEqual([3, 2, 1]);
    expect(done).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1000);
    expect(done).toHaveBeenCalledOnce();
  });

  it("stops when asked", () => {
    vi.useFakeTimers();
    const done = vi.fn();
    const stop = countdown(2, () => {}, done);
    stop();
    vi.advanceTimersByTime(5000);
    expect(done).not.toHaveBeenCalled();
  });
});

describe("after Finish", () => {
  const client = (answers: (SetupProgress | Error)[]): SetupClient =>
    ({
      state: vi.fn(async () => {
        const a = answers.shift()!;
        if (a instanceof Error) throw a;
        return a;
      }),
    }) as unknown as SetupClient;

  it("reads a closed listener as done", async () => {
    const c = client([progress("committing"), new UnreachableError("gone")]);
    expect(await waitForCommit(c, { sleep: noSleep })).toEqual({ kind: "gone" });
  });

  it("gives up after the limit while the box still says committing", async () => {
    let t = 0;
    const c = client(Array.from({ length: 30 }, () => progress("committing")));
    const r = await waitForCommit(c, { sleep: async (ms) => void (t += ms), now: () => t, limitMs: 20_000 });
    expect(r).toEqual({ kind: "stuck" });
    expect(t).toBe(20_000);
  });

  it("notices an answer changed meanwhile", async () => {
    const c = client([progress("done")]);
    expect(await waitForCommit(c, { sleep: noSleep })).toEqual({ kind: "reopened", progress: progress("done") });
  });
});
