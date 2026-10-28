import { describe, expect, it } from "vitest";
import { JellyfinClient, toCard, type JellyfinItem } from "./jellyfin.js";
import { buildServer, EventBus } from "../server.js";
import { loadConfig } from "../config.js";
import type { AppRunner } from "../session/appRunner.js";

const H = { host: "127.0.0.1:7300" };
const json = (b: unknown) => new Response(JSON.stringify(b), { headers: { "content-type": "application/json" } });

describe("JellyfinClient", () => {
  it("authenticate sends MediaBrowser header and returns token/userId", async () => {
    let seen: { url: string; headers: Headers; body: string } | undefined;
    const fake = (async (url: string, init: RequestInit) => {
      seen = { url, headers: new Headers(init.headers), body: String(init.body) };
      return json({ AccessToken: "tok", User: { Id: "u1" } });
    }) as unknown as typeof fetch;
    const c = new JellyfinClient({ url: "http://jf:8096/", fetch: fake });
    expect(await c.authenticate("vela", "pw")).toEqual({ token: "tok", userId: "u1" });
    expect(seen!.url).toBe("http://jf:8096/Users/AuthenticateByName");
    expect(seen!.headers.get("authorization")).toMatch(/^MediaBrowser Client="Vela", Device="Vela", DeviceId="[^"]+", Version="0.1.0"/);
    expect(JSON.parse(seen!.body)).toEqual({ Username: "vela", Pw: "pw" });
  });

  it("imageUrl includes maxWidth=3840", () => {
    const c = new JellyfinClient({ url: "http://jf:8096", token: "t", userId: "u" });
    const url = c.imageUrl("abc", "Backdrop", 3840);
    expect(url).toContain("/Items/abc/Images/Backdrop");
    expect(url).toContain("maxWidth=3840");
  });

  it("resume maps items and sends the userId", async () => {
    let url = "";
    const fake = (async (u: string) => {
      url = u;
      return json({
        Items: [{ Id: "i1", Name: "Sintel", ProductionYear: 2010, RunTimeTicks: 6_000_000_000, UserData: { PlaybackPositionTicks: 3_000_000_000 }, Genres: ["Fantasy"], BackdropImageTags: ["b"], ImageTags: { Primary: "p" }, Container: "mkv" }],
      });
    }) as unknown as typeof fetch;
    const c = new JellyfinClient({ url: "http://jf", token: "t", userId: "u1", fetch: fake });
    const items = await c.resume(5);
    expect(url).toContain("/UserItems/Resume?");
    expect(url).toContain("userId=u1");
    expect(items[0]).toMatchObject({ id: "i1", name: "Sintel", year: 2010, positionTicks: 3_000_000_000, hasBackdrop: true, hasPrimary: true });
  });
});

describe("toCard", () => {
  it("computes progress 0.5 and remainingMin 5 for 10-min item at 5 min", () => {
    const item: JellyfinItem = { id: "i1", name: "X", runTimeTicks: 6_000_000_000, positionTicks: 3_000_000_000, genres: [], hasBackdrop: true, hasPrimary: true };
    const card = toCard(item);
    expect(card.progress).toBeCloseTo(0.5);
    expect(card.remainingMin).toBe(5);
    expect(card.runtimeMin).toBe(10);
    expect(card.key).toBe("jellyfin:i1");
    expect(card.backdropUrl).toBe("/api/art/jellyfin/i1/Backdrop?w=3840");
  });

  it("toCard uses the fallback backdrop when the item has none", () => {
    const item: JellyfinItem = { id: "i2", name: "No art", genres: ["Drama"], overview: "Text", hasBackdrop: false, hasPrimary: true };
    const c = toCard(item, "/api/art/mock/bd-x.jpg");
    expect(c.backdropUrl).toBe("/api/art/mock/bd-x.jpg");
    expect(c.title).toBe("No art");
    expect(c.blurb).toBe("Text");
    expect(c.posterUrl).toBe("/api/art/jellyfin/i2/Primary?w=704");
  });
});

describe("art proxy", () => {
  const runner = { launch: async () => ({ ok: true }), goHome: async () => {}, current: () => "home" } as unknown as AppRunner;
  const mk = (fake: typeof fetch) =>
    buildServer({
      config: loadConfig("/nonexistent"),
      manifests: [],
      runner,
      bus: new EventBus(),
      jellyfin: new JellyfinClient({ url: "http://jf", token: "t", userId: "u", fetch: fake }),
    });

  it("rejects w=1920 with 400", async () => {
    const app = mk((async () => json({})) as unknown as typeof fetch);
    const r = await app.inject({ url: "/api/art/jellyfin/0123456789abcdef0123456789abcdef/Backdrop?w=1920", headers: H });
    expect(r.statusCode).toBe(400);
  });

  it("proxies allowed widths with the token and a cache header", async () => {
    let seen: { url: string; auth: string | null } | undefined;
    const fake = (async (u: string, init: RequestInit) => {
      seen = { url: u, auth: new Headers(init?.headers).get("authorization") };
      return new Response(new Uint8Array([1, 2, 3]), { headers: { "content-type": "image/jpeg" } });
    }) as unknown as typeof fetch;
    const r = await mk(fake).inject({ url: "/api/art/jellyfin/0123456789abcdef0123456789abcdef/Backdrop?w=3840", headers: H });
    expect(r.statusCode).toBe(200);
    expect(r.headers["cache-control"]).toBe("max-age=86400");
    expect(r.rawPayload.length).toBe(3);
    expect(seen!.url).toContain("maxWidth=3840");
    expect(seen!.auth).toContain('Token="t"');
  });

  it("proxy rejects text/html upstream with 502", async () => {
    const fake = (async () => new Response("<script>alert(1)</script>", { headers: { "content-type": "text/html" } })) as unknown as typeof fetch;
    const r = await mk(fake).inject({ url: "/api/art/jellyfin/0123456789abcdef0123456789abcdef/Backdrop?w=3840", headers: H });
    expect(r.statusCode).toBe(502);
  });

  it("proxied image carries nosniff and CSP", async () => {
    const fake = (async () => new Response(new Uint8Array([1]), { headers: { "content-type": "image/webp" } })) as unknown as typeof fetch;
    const r = await mk(fake).inject({ url: "/api/art/jellyfin/0123456789abcdef0123456789abcdef/Primary?w=704", headers: H });
    expect(r.statusCode).toBe(200);
    expect(r.headers["x-content-type-options"]).toBe("nosniff");
    expect(r.headers["content-security-policy"]).toBe("default-src 'none'; sandbox");
  });

  it("rejects odd ids and types", async () => {
    const app = mk((async () => json({})) as unknown as typeof fetch);
    expect((await app.inject({ url: "/api/art/jellyfin/0123456789abcdef0123456789abcdef/Logo?w=3840", headers: H })).statusCode).toBe(400);
    expect((await app.inject({ url: "/api/art/jellyfin/a%2F..%2Fb/Backdrop?w=3840", headers: H })).statusCode).toBe(400);
  });
});

describe("static asset routes", () => {
  const runner = {} as unknown as AppRunner;
  const app = buildServer({ config: loadConfig("/nonexistent"), manifests: [], runner, bus: new EventBus() });
  it("serves glyphs and mock art, rejects traversal", async () => {
    expect((await app.inject({ url: "/api/glyphs/plex.svg", headers: H })).statusCode).toBe(200);
    expect((await app.inject({ url: "/api/glyphs/..%2F..%2Fpackage.json", headers: H })).statusCode).toBe(404);
    expect((await app.inject({ url: "/api/glyphs/nope.svg", headers: H })).statusCode).toBe(404);
    const art = await app.inject({ url: "/api/art/mock/bd-tos-1.jpg", headers: H });
    expect(art.statusCode).toBe(200);
    expect(art.headers["content-type"]).toBe("image/jpeg");
    expect((await app.inject({ url: "/api/art/mock/..%2Fx.jpg", headers: H })).statusCode).toBe(404);
    for (const r of [art, await app.inject({ url: "/api/glyphs/plex.svg", headers: H })]) {
      expect(r.headers["x-content-type-options"]).toBe("nosniff");
      expect(r.headers["content-security-policy"]).toBe("default-src 'none'; sandbox");
    }
  });
});
