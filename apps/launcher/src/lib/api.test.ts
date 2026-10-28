import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { HomeState } from "@vela/shared";
import { connect } from "./api.js";

class FakeSocket {
  static all: FakeSocket[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  constructor(public url: string) {
    FakeSocket.all.push(this);
  }
  close() {
    this.onclose?.();
  }
}

const state: HomeState = { apps: [], rows: [], status: {} };

describe("connect", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    FakeSocket.all = [];
    vi.stubGlobal("WebSocket", FakeSocket);
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("retries until the daemon answers", async () => {
    const fetchMock = vi
      .fn()
      .mockRejectedValueOnce(new TypeError("connection refused"))
      .mockResolvedValueOnce(new Response("down", { status: 503 }))
      .mockResolvedValueOnce(Response.json(state));
    vi.stubGlobal("fetch", fetchMock);

    const api = connect("http://127.0.0.1:7300", () => {}, { retryMs: 1000 });
    let result: HomeState | undefined;
    void api.home().then((s) => (result = s));

    await vi.advanceTimersByTimeAsync(0);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1000);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(result).toBeUndefined();
    await vi.advanceTimersByTimeAsync(1000);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(result).toEqual(state);
    expect(fetchMock.mock.calls[0]![0]).toBe("http://127.0.0.1:7300/api/home");
  });

  it("older response does not overwrite newer", async () => {
    const older: HomeState = { apps: [], rows: [], status: { insideTemp: "old" } };
    const newer: HomeState = { apps: [], rows: [], status: { insideTemp: "new" } };
    let releaseOlder!: (r: Response) => void;
    const fetchMock = vi
      .fn()
      .mockReturnValueOnce(new Promise<Response>((r) => (releaseOlder = r)))
      .mockResolvedValueOnce(Response.json(newer));
    vi.stubGlobal("fetch", fetchMock);
    const api = connect("http://127.0.0.1:7300", () => {});
    const first = api.home();
    const second = api.home();
    expect(await second).toEqual(newer);
    releaseOlder(Response.json(older));
    expect(await first).toEqual(newer);
  });

  it("reconnects the event socket and delivers events", async () => {
    const events: unknown[] = [];
    connect("http://127.0.0.1:7300", (e) => events.push(e), { retryMs: 500 });
    expect(FakeSocket.all).toHaveLength(1);
    expect(FakeSocket.all[0]!.url).toBe("ws://127.0.0.1:7300/api/events");
    FakeSocket.all[0]!.onmessage?.({ data: JSON.stringify({ type: "home" }) });
    FakeSocket.all[0]!.onmessage?.({ data: "not json" });
    expect(events).toEqual([{ type: "home" }]);
    FakeSocket.all[0]!.close();
    await vi.advanceTimersByTimeAsync(499);
    expect(FakeSocket.all).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(FakeSocket.all).toHaveLength(2);
  });

  it("asks for a home refetch when the socket reconnects, not on first connect", async () => {
    const events: unknown[] = [];
    connect("http://127.0.0.1:7300", (e) => events.push(e), { retryMs: 500 });
    FakeSocket.all[0]!.onopen?.();
    expect(events).toEqual([]);
    FakeSocket.all[0]!.close();
    await vi.advanceTimersByTimeAsync(500);
    FakeSocket.all[1]!.onopen?.();
    expect(events).toEqual([{ type: "home-state-changed" }]);
  });

  it("POSTs JSON with a body and maps 409 to a reason", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(Response.json({ reason: "installing" }, { status: 409 }))
      .mockResolvedValueOnce(Response.json({ ok: true }));
    vi.stubGlobal("fetch", fetchMock);
    const api = connect("http://127.0.0.1:7300", () => {});
    expect(await api.launch("plex")).toEqual({ ok: false, reason: "installing" });
    await api.goHome();
    const [url, init] = fetchMock.mock.calls[1]!;
    expect(url).toBe("http://127.0.0.1:7300/api/home/go");
    expect(init.method).toBe("POST");
    expect(init.headers["content-type"]).toBe("application/json");
    expect(init.body).toBe("{}");
  });
});
