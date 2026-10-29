import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AppStatus, VelaEvent } from "@vela/shared";
import type { AppManifest } from "../manifests.js";
import { AppRunner } from "./appRunner.js";
import type { Sway, SwayWindow } from "./sway.js";

const plex: AppManifest = { id: "plex", name: "Plex", glyph: "plex.svg", brandColor: "x", kind: "flatpak", flatpak: "tv.plex.PlexHTPC", windowAppId: "plex" };
const steam: AppManifest = { id: "steam", name: "Steam", glyph: "steam.svg", brandColor: "x", kind: "command", command: ["steam", "-gamepadui"], windowAppId: "steam" };
const home: AppManifest = { id: "home", name: "Home", glyph: "home.svg", brandColor: "x", kind: "builtin", windowAppId: "^$" };

function setup(status: AppStatus = "ready") {
  let wins: SwayWindow[] = [];
  const newCbs: Array<(w: SwayWindow) => void> = [];
  const closeCbs: Array<(w: SwayWindow) => void> = [];
  const shown: string[] = [];
  const ops: string[] = [];
  const sway: Sway = {
    moveWindowToWorkspace: async (id, n) => void ops.push(`move:${id}:${n}`),
    windows: async () => wins,
    showWorkspace: async (n) => void (shown.push(n), ops.push(`show:${n}`)),
    onWindowNew: (cb) => (newCbs.push(cb), () => {}),
    onWindowClosed: (cb) => (closeCbs.push(cb), () => {}),
  };
  const errCbs: Array<(e: Error) => void> = [];
  const spawn = vi.fn((_cmd: string[], _env: Record<string, string>) => ({ pid: 42, onError: (cb: (e: Error) => void) => void errCbs.push(cb) }));
  const events: VelaEvent[] = [];
  const runner = new AppRunner({ sway, manifests: [plex, steam, home], spawn, status: async () => status, emit: (e) => events.push(e) });
  const open = (w: Partial<SwayWindow>) => {
    const win = { id: 1, appId: null, cls: null, workspace: "x", pid: 1, ...w };
    wins = [...wins, win];
    newCbs.forEach((c) => c(win));
    return win;
  };
  const close = (win: SwayWindow) => {
    wins = wins.filter((x) => x.id !== win.id);
    closeCbs.forEach((c) => c(win));
  };
  return { runner, spawn, events, shown, open, close, ops, errCbs };
}
const flush = () => new Promise<void>((r) => setImmediate(r));

describe("AppRunner", () => {
  beforeEach(() => vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] }));
  afterEach(() => vi.useRealTimers());

  it("launches a ready app on its own workspace", async () => {
    const t = setup();
    expect(await t.runner.launch("plex")).toEqual({ ok: true });
    expect(t.spawn).toHaveBeenCalledTimes(1);
    expect(t.spawn.mock.calls[0][0]).toEqual(["flatpak", "run", "tv.plex.PlexHTPC"]);
    t.open({ id: 1, appId: "tv.plex.PlexHTPC" });
    await flush();
    expect(t.shown).toContain("app-plex");
    expect(t.events).toContainEqual({ type: "app-state", id: "plex", status: "running" });
    expect(t.runner.current()).toBe("plex");
  });

  it("second launch of a running app only switches", async () => {
    const t = setup();
    await t.runner.launch("plex");
    t.open({ appId: "plex" });
    await flush();
    t.shown.length = 0;
    await t.runner.launch("plex");
    expect(t.spawn).toHaveBeenCalledTimes(1);
    expect(t.shown).toEqual(["app-plex"]);
  });

  it("refuses to launch an app that is still installing", async () => {
    const t = setup("installing");
    expect(await t.runner.launch("plex")).toEqual({ ok: false, reason: "installing" });
    expect(t.spawn).not.toHaveBeenCalled();
    expect(t.events).toContainEqual({ type: "toast", message: "Plex is still installing" });
  });

  it("refuses a missing app with install guidance", async () => {
    const t = setup("missing");
    expect(await t.runner.launch("plex")).toEqual({ ok: false, reason: "missing" });
    expect(t.events).toContainEqual({ type: "toast", message: "Plex isn't installed yet. It will install automatically when the box is online." });
  });

  it("rejects unknown ids and treats home as a no-op", async () => {
    const t = setup();
    expect(await t.runner.launch("nope")).toEqual({ ok: false, reason: "unknown" });
    expect(await t.runner.launch("home")).toEqual({ ok: true });
    expect(t.spawn).not.toHaveBeenCalled();
    expect(t.shown).toEqual([]);
  });

  it("returns home when the app's last window closes", async () => {
    const t = setup();
    await t.runner.launch("plex");
    const a = t.open({ id: 1, appId: "plex" });
    const b = t.open({ id: 2, appId: "plex" });
    await flush();
    t.shown.length = 0;
    t.events.length = 0;
    t.close(a);
    await flush();
    expect(t.shown).toEqual([]);
    t.close(b);
    await flush();
    expect(t.shown).toEqual(["home"]);
    expect(t.events).toContainEqual({ type: "app-state", id: "plex", status: "ready" });
    expect(t.events).toContainEqual({ type: "home" });
    expect(t.runner.current()).toBe("home");
  });

  it("goHome from home is a no-op that still emits home", async () => {
    const t = setup();
    await t.runner.goHome();
    expect(t.runner.current()).toBe("home");
    expect(t.events).toEqual([{ type: "home" }]);
  });

  it("matches an X11 window by class", async () => {
    const t = setup();
    await t.runner.launch("steam");
    t.open({ appId: null, cls: "steam" });
    await flush();
    expect(t.shown).toContain("app-steam");
    expect(t.events).toContainEqual({ type: "app-state", id: "steam", status: "running" });
  });

  it("goes home with a toast when no window appears", async () => {
    const t = setup();
    await t.runner.launch("plex");
    await vi.advanceTimersByTimeAsync(10_000);
    expect(t.shown).toEqual(["home"]);
    expect(t.events).toContainEqual({ type: "toast", message: "Plex didn't start" });
    expect(t.events).toContainEqual({ type: "home" });
  });

  it("does not time out once the window appeared", async () => {
    const t = setup();
    await t.runner.launch("plex");
    t.open({ appId: "plex" });
    await flush();
    await vi.advanceTimersByTimeAsync(11_000);
    expect(t.runner.current()).toBe("plex");
  });

  it("moves the window to its workspace before switching", async () => {
    const t = setup();
    await t.runner.launch("plex");
    t.open({ id: 7, appId: "plex" });
    await flush();
    expect(t.ops).toEqual(["move:7:app-plex", "show:app-plex"]);
  });

  it("moves a matching window even with no launch pending, without switching", async () => {
    const t = setup();
    t.open({ id: 9, appId: "plex" });
    await flush();
    expect(t.ops).toEqual(["move:9:app-plex"]);
    expect(t.runner.current()).toBe("home");
    expect(t.events).toContainEqual({ type: "app-state", id: "plex", status: "running" });
  });

  it("late window after timeout still switches when user is still home", async () => {
    const t = setup();
    await t.runner.launch("plex");
    await vi.advanceTimersByTimeAsync(10_000);
    t.ops.length = 0;
    t.events.length = 0;
    t.open({ id: 3, appId: "plex" });
    await flush();
    expect(t.ops).toEqual(["move:3:app-plex", "show:app-plex"]);
    expect(t.runner.current()).toBe("plex");
    expect(t.events).toContainEqual({ type: "app-state", id: "plex", status: "running" });
  });

  it("late window does not switch if the user left home", async () => {
    const t = setup();
    await t.runner.launch("plex");
    await vi.advanceTimersByTimeAsync(10_000);
    await t.runner.launch("steam");
    t.open({ id: 4, appId: "steam" });
    await flush();
    t.ops.length = 0;
    t.open({ id: 5, appId: "plex" });
    await flush();
    expect(t.ops).toEqual(["move:5:app-plex"]);
    expect(t.runner.current()).toBe("steam");
  });

  it("goes home immediately with a toast when spawn errors", async () => {
    const t = setup();
    await t.runner.launch("plex");
    t.errCbs.forEach((cb) => cb(new Error("ENOENT")));
    await flush();
    expect(t.events).toContainEqual({ type: "toast", message: "Plex didn't start" });
    expect(t.shown).toEqual(["home"]);
    await vi.advanceTimersByTimeAsync(10_000);
    expect(t.events.filter((e) => e.type === "toast")).toHaveLength(1);
  });

  it("places the mpv window on app-player and shows it when playback is expected", async () => {
    const t = setup();
    t.runner.expectPlayer();
    t.open({ id: 9, appId: "mpv" });
    await flush();
    expect(t.ops).toEqual(["move:9:app-player", "show:app-player"]);
    expect(t.runner.current()).toBe("player");
  });

  it("moves an unexpected mpv window without switching to it", async () => {
    const t = setup();
    t.open({ id: 9, appId: "mpv" });
    await flush();
    expect(t.ops).toEqual(["move:9:app-player"]);
    expect(t.events).toEqual([]);
  });

  it("mpv window emits app-state running for player", async () => {
    const t = setup();
    t.runner.expectPlayer();
    t.open({ id: 9, appId: "mpv" });
    await flush();
    expect(t.events).toEqual([{ type: "app-state", id: "player", status: "running" }]);
  });
});
