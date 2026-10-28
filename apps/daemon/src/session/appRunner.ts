import { spawn as nodeSpawn } from "node:child_process";
import type { AppStatus, VelaEvent } from "@vela/shared";
import type { AppManifest } from "../manifests.js";
import type { Sway, SwayWindow } from "./sway.js";

const LAUNCH_TIMEOUT_MS = 10_000;

export interface AppRunnerDeps {
  sway: Sway;
  manifests: AppManifest[];
  spawn: (cmd: string[], env: Record<string, string>) => { pid: number; onError?: (cb: (e: Error) => void) => void };
  status: (m: AppManifest) => Promise<AppStatus>;
  emit: (e: VelaEvent) => void;
}

export type LaunchResult = { ok: true } | { ok: false; reason: "unknown" | "installing" | "missing" };

export function defaultSpawn(cmd: string[], env: Record<string, string>): { pid: number; onError: (cb: (e: Error) => void) => void } {
  const [bin = "", ...args] = cmd;
  const p = nodeSpawn(bin, args, { env: { ...process.env, ...env }, detached: true, stdio: "ignore" });
  p.on("error", (e) => console.error(`vela: failed to spawn ${bin}: ${e.message}`));
  p.unref();
  return { pid: p.pid ?? 0, onError: (cb) => void p.on("error", cb) };
}

export class AppRunner {
  private cur: string = "home";
  private pending = new Map<string, ReturnType<typeof setTimeout>>();
  /** ids whose launch timed out; a late window still switches if the user is still home. */
  private timedOut = new Set<string>();
  /** Set by expectPlayer(); the next mpv window is shown, not just placed. */
  private playerPending = false;

  constructor(private deps: AppRunnerDeps) {
    deps.sway.onWindowNew((w) => void this.onNew(w).catch(this.logErr));
    deps.sway.onWindowClosed((w) => void this.onClosed(w).catch(this.logErr));
  }

  private logErr = (e: unknown) => console.error("vela: appRunner", (e as Error).message);

  private matches(m: AppManifest, w: SwayWindow): boolean {
    if (m.kind === "builtin") return false;
    return new RegExp(m.windowAppId, "i").test(w.appId ?? w.cls ?? "");
  }

  private appFor(w: SwayWindow): AppManifest | undefined {
    return this.deps.manifests.find((m) => this.matches(m, w));
  }

  private argv(m: AppManifest): string[] {
    return m.kind === "flatpak" ? ["flatpak", "run", m.flatpak!, ...(m.args ?? [])] : [...m.command!, ...(m.args ?? [])];
  }

  current(): string {
    return this.cur;
  }

  async launch(id: string): Promise<LaunchResult> {
    const m = this.deps.manifests.find((x) => x.id === id);
    if (!m) return { ok: false, reason: "unknown" };
    if (m.kind === "builtin") return { ok: true };

    const status = await this.deps.status(m);
    if (status === "installing") {
      this.deps.emit({ type: "toast", message: `${m.name} is still installing` });
      return { ok: false, reason: "installing" };
    }
    if (status === "missing") {
      this.deps.emit({ type: "toast", message: `${m.name} isn't installed yet. It will install automatically when the box is online.` });
      return { ok: false, reason: "missing" };
    }

    const running = (await this.deps.sway.windows()).some((w) => this.matches(m, w));
    if (running) {
      await this.deps.sway.showWorkspace(`app-${id}`);
      this.cur = id;
      return { ok: true };
    }
    if (this.pending.has(id)) return { ok: true }; // already starting

    this.timedOut.delete(id);
    const fail = (): void => {
      const t = this.pending.get(id);
      if (t === undefined) return;
      clearTimeout(t);
      this.pending.delete(id);
      this.timedOut.add(id);
      this.deps.emit({ type: "toast", message: `${m.name} didn't start` });
      void this.goHome().catch(this.logErr);
    };
    this.pending.set(id, setTimeout(fail, LAUNCH_TIMEOUT_MS));
    try {
      this.deps.spawn(this.argv(m), {}).onError?.(fail);
    } catch {
      fail();
    }
    return { ok: true };
  }

  /** Playback is about to start: the next "mpv" window goes to workspace app-player and is shown. */
  expectPlayer(): void {
    this.playerPending = true;
  }

  async goHome(): Promise<void> {
    this.playerPending = false;
    await this.deps.sway.showWorkspace("home");
    this.cur = "home";
    this.deps.emit({ type: "home" });
  }

  private async onNew(w: SwayWindow): Promise<void> {
    if (w.appId === "mpv") {
      await this.deps.sway.moveWindowToWorkspace(w.id, "app-player");
      if (this.playerPending) {
        this.playerPending = false;
        await this.deps.sway.showWorkspace("app-player");
        this.cur = "player";
      }
      return;
    }
    const m = this.appFor(w);
    if (!m) return;
    const ws = `app-${m.id}`;
    await this.deps.sway.moveWindowToWorkspace(w.id, ws); // always before any switch
    const timer = this.pending.get(m.id);
    const late = this.timedOut.has(m.id) && this.cur === "home";
    if (timer || late) {
      if (timer) clearTimeout(timer);
      this.pending.delete(m.id);
      this.timedOut.delete(m.id);
      await this.deps.sway.showWorkspace(ws);
      this.cur = m.id;
    }
    this.deps.emit({ type: "app-state", id: m.id, status: "running" });
  }

  private async onClosed(w: SwayWindow): Promise<void> {
    const m = this.appFor(w);
    if (!m) return;
    const left = (await this.deps.sway.windows()).some((x) => x.id !== w.id && this.matches(m, x));
    if (left) return;
    this.deps.emit({ type: "app-state", id: m.id, status: "ready" });
    if (this.cur === m.id) await this.goHome();
  }
}
