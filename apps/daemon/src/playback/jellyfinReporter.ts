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

  session.onTime((sec, paused) => {
    sawTime = true;
    const t = now();
    if (paused !== lastPaused || t - lastReport >= PROGRESS_EVERY_MS) {
      lastPaused = paused;
      lastReport = t;
      jf.reportProgress(itemId, ticks(sec), paused).catch(quiet("progress"));
    }
  });

  const lastSec = await new Promise<number>((resolve) => session.onExit(resolve));
  const failed = !sawTime && now() - startedAt < MIN_PLAY_MS;
  await jf.reportStopped(itemId, ticks(lastSec)).catch(quiet("stopped"));
  if (failed) deps.emit?.({ type: "toast", message: START_FAILED });
  await deps.goHome();
}

/** One playback at a time: a new request quits the current one (which reports stopped) first. */
export function createPlayer(deps: Omit<PlayDeps, "onSession"> & { runner: Pick<AppRunner, "expectPlayer"> }): (itemId: string) => Promise<void> {
  let current: { quit: () => void; done: Promise<void> } | null = null;
  return async (itemId) => {
    if (current) {
      const prev = current;
      prev.quit();
      await prev.done;
    }
    deps.runner.expectPlayer();
    let session: MpvSession | undefined;
    let quitRequested = false;
    const done = playJellyfin(itemId, {
      ...deps,
      onSession: (s) => {
        session = s;
        if (quitRequested) s.quit(); // superseded while still looking up the resume position
      },
    }).catch(quiet("playback"));
    const entry = {
      quit: () => ((quitRequested = true), session?.quit()),
      done,
    };
    current = entry;
    void done.then(() => void (current === entry && (current = null)));
  };
}
