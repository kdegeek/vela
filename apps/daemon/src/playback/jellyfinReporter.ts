import type { VelaEvent } from "@vela/shared";
import type { JellyfinClient } from "../media/jellyfin.js";
import type { AppRunner } from "../session/appRunner.js";
import type { MpvSession, startMpv } from "./mpv.js";

const TICKS_PER_SEC = 10_000_000;
const PROGRESS_EVERY_MS = 10_000;
const MIN_PLAY_MS = 2_000;
export const START_FAILED = "Playback didn't start. Check that the Jellyfin server is reachable.";

export interface PlayDeps {
  jf: JellyfinClient;
  start: typeof startMpv;
  goHome: () => Promise<void>;
  now?: () => number;
  emit?: (e: VelaEvent) => void;
  /** Called with the live session so a caller can stop it. */
  onSession?: (s: MpvSession) => void;
}

const quiet = (what: string) => (e: unknown) => console.error(`vela: Jellyfin ${what} report failed:`, (e as Error).message);

/** Plays a Jellyfin item in mpv and mirrors position to Jellyfin. Resolves after mpv exits and the launcher is shown again. */
export async function playJellyfin(itemId: string, deps: PlayDeps): Promise<void> {
  const { jf } = deps;
  const now = deps.now ?? Date.now;
  let startTicks = 0;
  try {
    startTicks = (await jf.resume(50)).find((i) => i.id === itemId)?.positionTicks ?? 0;
  } catch (e) {
    quiet("resume lookup")(e);
  }
  const startSec = Math.floor(startTicks / TICKS_PER_SEC);
  const ticks = (sec: number) => Math.round(sec * TICKS_PER_SEC);

  const startedAt = now();
  let lastReport = startedAt;
  let lastPaused = false;
  let sawTime = false;
  const session = deps.start(jf.streamUrl(itemId), startSec);
  deps.onSession?.(session);
  jf.reportStart(itemId, startTicks).catch(quiet("start"));

  session.onTime((sec, paused, fromTime) => {
    if (fromTime) sawTime = true;
    const t = now();
    if (paused !== lastPaused || t - lastReport >= PROGRESS_EVERY_MS) {
      lastPaused = paused;
      lastReport = t;
      jf.reportProgress(itemId, ticks(sec), paused).catch(quiet("progress"));
    }
  });

  const { lastSec, ipcFailed } = await new Promise<{ lastSec: number; ipcFailed: boolean }>((resolve) =>
    session.onExit((lastSec, ipcFailed) => resolve({ lastSec, ipcFailed })),
  );
  const failed = ipcFailed || (!sawTime && now() - startedAt < MIN_PLAY_MS);
  await jf.reportStopped(itemId, ticks(lastSec)).catch(quiet("stopped"));
  // Continue watching changed; the launcher refetches now rather than at the next poll.
  deps.emit?.({ type: "home-state-changed" });
  if (failed) deps.emit?.({ type: "toast", message: START_FAILED });
  await deps.goHome();
}

/**
 * One playback at a time: a new request quits the current one (which reports stopped) first. Plays are serialized synchronously.
 * Registers a stop with the runner, so going home (remote Home, vela-ctl home) quits the movie too.
 */
export function createPlayer(deps: Omit<PlayDeps, "onSession"> & { runner: Pick<AppRunner, "expectPlayer" | "setPlayerStop"> }): (itemId: string) => Promise<void> {
  let current: { quit: () => void; done: Promise<void> } | null = null;
  deps.runner.setPlayerStop(() => current?.quit());
  return (itemId) => {
    const prev = current;
    let session: MpvSession | undefined;
    let quitRequested = false;
    const entry: { quit: () => void; done: Promise<void> } = { quit: () => ((quitRequested = true), session?.quit()), done: Promise.resolve() };
    current = entry;
    entry.done = (async () => {
      if (prev) {
        prev.quit();
        await prev.done;
      }
      if (quitRequested) return; // superseded before it ever started
      deps.runner.expectPlayer();
      await playJellyfin(itemId, {
        ...deps,
        // A superseded play leaves the screen to the next one; going home here would stop it.
        goHome: () => (current === entry ? deps.goHome() : Promise.resolve()),
        onSession: (s) => {
          session = s;
          if (quitRequested) s.quit(); // superseded while still looking up the resume position
        },
      });
    })().catch(quiet("playback"));
    void entry.done.then(() => void (current === entry && (current = null)));
    return Promise.resolve();
  };
}
