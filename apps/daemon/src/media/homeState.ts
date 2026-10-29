import type { AppStatus, AppTile, HeroItem, HomeState, MediaCard, Row, VelaEvent } from "@vela/shared";
import type { AppManifest } from "../manifests.js";
import { glyphUrl } from "./glyphs.js";
import { JellyfinAuthError } from "./jellyfin.js";
import type { MockProvider } from "./mock.js";

export const JELLYFIN_DOWN = "Jellyfin isn't reachable. Check that the server is on.";
export const JELLYFIN_AUTH_FAILED = "Jellyfin sign-in failed. Check the username and password in /etc/vela/vela.toml.";
const FALLBACK_SLUG = "charge";

export interface HomeDeps {
  manifests: AppManifest[];
  status: (m: AppManifest) => Promise<AppStatus>;
  media: { resume(): Promise<MediaCard[]> } | null;
  mock: MockProvider;
  insideTemp?: () => Promise<string | undefined>;
  /** config.ui.clock24h, passed to the launcher clock. */
  clock24h?: boolean;
  /** config.ui.effects; the launcher resolves "auto" with a perf run. */
  effects?: "auto" | "full" | "lite";
  /** Glyph data URLs from loadGlyphs(), by file name. */
  glyphs?: ReadonlyMap<string, string>;
}

function heroFrom(c: MediaCard, appId: string): HeroItem {
  const { key: _key, posterUrl: _poster, ...hero } = c;
  return { ...hero, sourceId: appId };
}

export async function buildHomeState(d: HomeDeps): Promise<HomeState> {
  let resume: MediaCard[] | undefined;
  let line: string | undefined;
  if (d.media) {
    try {
      resume = await d.media.resume();
    } catch (e) {
      line = e instanceof JellyfinAuthError ? JELLYFIN_AUTH_FAILED : JELLYFIN_DOWN;
    }
  }

  const rows: Row[] = [];
  if (!d.media) rows.push(...d.mock.rows());
  else if (resume) rows.push({ id: "continue", title: "Continue watching", layout: "wide", items: resume });

  const ordered = [...d.manifests].sort(
    (a, b) => (a.order ?? Infinity) - (b.order ?? Infinity) || a.name.localeCompare(b.name),
  );
  const apps = await Promise.all(
    ordered.map(async (m): Promise<AppTile> => {
      let feature: HeroItem | null = null;
      const f = m.feature;
      if (f?.kind === "mock") feature = { ...d.mock.hero(f.slug), sourceId: m.id };
      else if (f?.kind === "static") feature = { ...f.item, sourceId: m.id };
      else if (f?.kind === "jellyfin-resume") {
        const first = resume?.[0];
        feature = first ? heroFrom(first, m.id) : { ...d.mock.hero(FALLBACK_SLUG), sourceId: m.id };
      }
      return { id: m.id, name: m.name, glyphUrl: glyphUrl(d.glyphs, m.glyph), brandColor: m.brandColor, status: await d.status(m), feature };
    }),
  );

  const status: HomeState["status"] = { clock24h: d.clock24h ?? false, effects: d.effects ?? "auto" };
  if (line) status.line = line;
  if (d.insideTemp) {
    try {
      const t = await d.insideTemp();
      if (t) status.insideTemp = t;
    } catch {
      // temperature is optional; leave it out
    }
  }
  return { apps, rows, status };
}

/** Rebuilds home state periodically and emits home-state-changed when it differs from the last build. */
export function startHomePolling(build: () => Promise<HomeState>, emit: (e: VelaEvent) => void, intervalMs = 60_000) {
  let last: string | undefined;
  const tick = async (): Promise<void> => {
    try {
      const cur = JSON.stringify(await build());
      if (last !== undefined && cur !== last) emit({ type: "home-state-changed" });
      last = cur;
    } catch (e) {
      console.error("vela: home poll failed:", (e as Error).message);
    }
  };
  const timer = setInterval(() => void tick(), intervalMs);
  timer.unref();
  return { tick, stop: () => clearInterval(timer) };
}
