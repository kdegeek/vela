import { describe, expect, it, vi } from "vitest";
import { haInsideTemp, haTv, TvUnreachable } from "./homeAssistant.js";

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

describe("haTv", () => {
  it("volume_up posts media_player.volume_up with entity_id and bearer token", async () => {
    const fetch = vi.fn(async () => json([]));
    await haTv("media_player.tv", { url: "http://ha:8123/", token: "tok", fetch }).command("volume_up");
    const [url, init] = fetch.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("http://ha:8123/api/services/media_player/volume_up");
    expect(init.method).toBe("POST");
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer tok");
    expect(JSON.parse(init.body as string)).toEqual({ entity_id: "media_player.tv" });
  });

  it("maps the other commands", async () => {
    const fetch = vi.fn(async () => json([]));
    const tv = haTv("media_player.tv", { url: "http://ha", token: "t", fetch });
    await tv.command("power_on");
    await tv.command("power_off");
    const calls = fetch.mock.calls as unknown as [string, RequestInit][];
    expect(calls.map((c) => c[0].split("/").pop())).toEqual(["turn_on", "turn_off"]);
  });

  it.each([
    [false, true],
    [true, false],
    [undefined, true],
  ])("mute toggles: is_volume_muted %s sends %s", async (muted, sent) => {
    const fetch = vi.fn(async (url: string) => (url.includes("/api/states/") ? json({ state: "on", attributes: muted === undefined ? {} : { is_volume_muted: muted } }) : json([])));
    await haTv("media_player.tv", { url: "http://ha", token: "t", fetch: fetch as unknown as typeof globalThis.fetch }).command("mute");
    const calls = fetch.mock.calls as unknown as [string, RequestInit][];
    expect(calls.map((c) => c[0])).toEqual(["http://ha/api/states/media_player.tv", "http://ha/api/services/media_player/volume_mute"]);
    expect(JSON.parse(calls[1]![1].body as string)).toEqual({ entity_id: "media_player.tv", is_volume_muted: sent });
  });

  it("HTTP 500 rejects with TvUnreachable", async () => {
    const fetch = vi.fn(async () => json({}, 500));
    await expect(haTv("media_player.tv", { url: "http://ha", token: "t", fetch }).command("mute")).rejects.toBeInstanceOf(TvUnreachable);
  });

  it("a network error rejects with TvUnreachable", async () => {
    const fetch = vi.fn(async () => {
      throw new Error("ECONNREFUSED");
    });
    await expect(haTv("media_player.tv", { url: "http://ha", token: "t", fetch }).command("mute")).rejects.toBeInstanceOf(TvUnreachable);
  });

  it("encodes the entity id in state URLs", async () => {
    const fetch = vi.fn(async () => json({ state: "on" }));
    await haTv("media_player.a/b?x", { url: "http://ha", token: "t", fetch }).state();
    expect((fetch.mock.calls[0] as unknown as [string])[0]).toBe("http://ha/api/states/media_player.a%2Fb%3Fx");
  });

  it("state reads GET /api/states/<entity>", async () => {
    const fetch = vi.fn(async () => json({ state: "off" }));
    expect(await haTv("media_player.tv", { url: "http://ha", token: "t", fetch }).state()).toBe("off");
    expect((fetch.mock.calls[0] as unknown as [string])[0]).toBe("http://ha/api/states/media_player.tv");
  });
});

describe("haInsideTemp", () => {
  it("formats state with unit", async () => {
    const fetch = vi.fn(async () => json({ state: "21.5", attributes: { unit_of_measurement: "°C" } }));
    expect(await haInsideTemp("sensor.t", { url: "http://ha", token: "t", fetch })).toBe("21.5°C");
  });
  it("is undefined when unavailable", async () => {
    const fetch = vi.fn(async () => json({ state: "unavailable", attributes: {} }));
    expect(await haInsideTemp("sensor.t", { url: "http://ha", token: "t", fetch })).toBeUndefined();
  });
});
