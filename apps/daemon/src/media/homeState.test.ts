import { describe, expect, it } from "vitest";
import { JellyfinAuthError } from "./jellyfin.js";
import { buildHomeState, startHomePolling } from "./homeState.js";
import { createMockProvider } from "./mock.js";
import { EventBus } from "../server.js";
import type { AppManifest } from "../manifests.js";
import type { MediaCard, VelaEvent } from "@vela/shared";

const m = (id: string, feature?: AppManifest["feature"]): AppManifest => ({ id, name: id, glyph: `${id}.svg`, brandColor: "red", kind: "command", command: ["true"], windowAppId: id, feature });
const mock = createMockProvider();
const manifests = [m("plex", { kind: "mock", slug: "tos" }), m("jellyfin", { kind: "jellyfin-resume" }), m("steam", { kind: "static", item: { title: "Big Picture", tags: [], backdropUrl: "/x.jpg" } }), m("home")];
const card = (k: string): MediaCard => ({ key: `jellyfin:${k}`, title: k, tags: [], backdropUrl: `/api/art/jellyfin/${k}/Backdrop?w=3840`, sourceId: "jellyfin", progress: 0.5 });

describe("buildHomeState", () => {
  it("apps carry status from status()", async () => {
    const s = await buildHomeState({ manifests, status: async (x) => (x.id === "plex" ? "installing" : "ready"), media: null, mock });
    expect(s.apps.find((a) => a.id === "plex")?.status).toBe("installing");
    expect(s.apps.find((a) => a.id === "steam")?.status).toBe("ready");
    expect(s.apps.find((a) => a.id === "plex")?.glyphUrl).toBe("/api/glyphs/plex.svg");
  });

  it("apps are ordered by manifest order", async () => {
    const ms = [{ ...m("steam"), order: 50 }, m("zeta"), { ...m("plex"), order: 10 }, m("alpha"), { ...m("youtube"), order: 20 }];
    const s = await buildHomeState({ manifests: ms, status: async () => "ready", media: null, mock });
    expect(s.apps.map((a) => a.id)).toEqual(["plex", "youtube", "steam", "alpha", "zeta"]);
  });

  it("passes the clock24h preference through in status", async () => {
    const on = await buildHomeState({ manifests, status: async () => "ready", media: null, mock, clock24h: true });
    expect(on.status.clock24h).toBe(true);
    const off = await buildHomeState({ manifests, status: async () => "ready", media: null, mock });
    expect(off.status.clock24h).toBe(false);
  });

  it("assigns hero features per manifest", async () => {
    const s = await buildHomeState({ manifests, status: async () => "ready", media: { resume: async () => [card("abc")] }, mock });
    expect(s.apps.find((a) => a.id === "plex")?.feature).toMatchObject({ title: "Tears of Steel", sourceId: "plex" });
    expect(s.apps.find((a) => a.id === "jellyfin")?.feature).toMatchObject({ title: "abc", sourceId: "jellyfin" });
    expect(s.apps.find((a) => a.id === "steam")?.feature).toMatchObject({ title: "Big Picture", sourceId: "steam" });
    expect(s.apps.find((a) => a.id === "home")?.feature).toBeNull();
  });

  it("hides Jellyfin rows when the server is unreachable", async () => {
    const s = await buildHomeState({ manifests, status: async () => "ready", media: { resume: async () => { throw new Error("ECONNREFUSED"); } }, mock });
    expect(s.rows.find((r) => r.id === "continue")).toBeUndefined();
    expect(s.status.line).toBe("Jellyfin isn't reachable. Check that the server is on.");
    expect(s.apps.find((a) => a.id === "jellyfin")?.feature).not.toBeNull(); // mock hero fallback
  });

  it("says sign-in failed, not unreachable, when Jellyfin rejects the credentials", async () => {
    const s = await buildHomeState({ manifests, status: async () => "ready", media: { resume: async () => { throw new JellyfinAuthError("401"); } }, mock });
    expect(s.status.line).toBe("Jellyfin sign-in failed. Check the username and password in /etc/vela/vela.toml.");
    expect(s.rows.find((r) => r.id === "continue")).toBeUndefined();
  });

  it("uses Jellyfin resume for the continue row when reachable", async () => {
    const s = await buildHomeState({ manifests, status: async () => "ready", media: { resume: async () => [card("a"), card("b")] }, mock });
    expect(s.rows.find((r) => r.id === "continue")?.items.map((i) => i.title)).toEqual(["a", "b"]);
    expect(s.rows.find((r) => r.id === "recent")).toBeUndefined();
    expect(s.status.line).toBeUndefined();
  });

  it("mock provider supplies continue + recent rows", async () => {
    const s = await buildHomeState({ manifests, status: async () => "ready", media: null, mock, insideTemp: async () => "21.5°" });
    const cont = s.rows.find((r) => r.id === "continue");
    expect(cont?.layout).toBe("wide");
    expect(cont?.items.length).toBeGreaterThan(0);
    expect(s.rows.find((r) => r.id === "recent")?.layout).toBe("poster");
    expect(s.status.insideTemp).toBe("21.5°");
  });
});

describe("startHomePolling", () => {
  it("emits home-state-changed only when the state differs", async () => {
    const bus = new EventBus();
    const events: VelaEvent[] = [];
    bus.subscribe((e) => events.push(e));
    let v = "a";
    const poll = startHomePolling(async () => ({ apps: [], rows: [], status: { line: v } }), bus.emit, 1_000_000);
    await poll.tick();
    expect(events).toEqual([]); // baseline
    await poll.tick();
    expect(events).toEqual([]);
    v = "b";
    await poll.tick();
    expect(events).toEqual([{ type: "home-state-changed" }]);
    poll.stop();
  });
});
