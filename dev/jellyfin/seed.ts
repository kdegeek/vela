// Seeds a fresh Jellyfin server for the integration test: completes the startup wizard, creates user vela/vela,
// adds a library containing a generated 4K HEVC Main10 file, scans it, records 50% progress, and creates an API key
// named "Vela".
// Run: JELLYFIN_URL=http://localhost:8096 VELA_MEDIA_DIR=/tmp/vela-media node --experimental-strip-types dev/jellyfin/seed.ts
// VELA_MEDIA_DIR is the host path mounted into the server container at VELA_MEDIA_MOUNT (default /media).
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const URL_BASE = (process.env.JELLYFIN_URL ?? "http://localhost:8096").replace(/\/+$/, "");
const MEDIA_DIR = process.env.VELA_MEDIA_DIR ?? "/tmp/vela-media";
const MEDIA_MOUNT = process.env.VELA_MEDIA_MOUNT ?? "/media";
const FILE = "Sintel (2010).mkv";
const AUTH = 'MediaBrowser Client="Vela", Device="Vela seed", DeviceId="vela-seed", Version="0.1.0"';

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function api(p: string, init: { method?: string; body?: unknown; token?: string } = {}): Promise<Response> {
  const res = await fetch(`${URL_BASE}${p}`, {
    method: init.method ?? (init.body === undefined ? "GET" : "POST"),
    headers: {
      authorization: init.token ? `${AUTH}, Token="${init.token}"` : AUTH,
      ...(init.body === undefined ? {} : { "content-type": "application/json" }),
    },
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
  });
  if (!res.ok) throw new Error(`${init.method ?? "GET"} ${p} -> ${res.status} ${await res.text().catch(() => "")}`);
  return res;
}

async function waitFor<T>(what: string, fn: () => Promise<T | undefined>, timeoutMs: number, everyMs = 2000): Promise<T> {
  const end = Date.now() + timeoutMs;
  let last: unknown;
  while (Date.now() < end) {
    try {
      const v = await fn();
      if (v !== undefined) return v;
    } catch (e) {
      last = e;
    }
    await sleep(everyMs);
  }
  throw new Error(`Timed out waiting for ${what}${last ? `: ${(last as Error).message}` : ""}`);
}

function makeVideo(): void {
  fs.mkdirSync(MEDIA_DIR, { recursive: true });
  const out = path.join(MEDIA_DIR, FILE);
  if (fs.existsSync(out)) return;
  execFileSync(
    "ffmpeg",
    ["-y", "-f", "lavfi", "-i", "testsrc2=size=3840x2160:rate=24", "-t", "10", "-c:v", "libx265", "-preset", "ultrafast", "-pix_fmt", "yuv420p10le", "-profile:v", "main10", "-x265-params", "log-level=error", "-tag:v", "hvc1", out],
    { stdio: "inherit" },
  );
}

async function wizard(): Promise<void> {
  const info = await (await api("/System/Info/Public")).json() as { StartupWizardCompleted?: boolean };
  if (info.StartupWizardCompleted) return;
  await api("/Startup/Configuration", { method: "POST", body: { UICulture: "en-US", MetadataCountryCode: "US", PreferredMetadataLanguage: "en" } });
  await api("/Startup/User"); // the server creates its default user lazily; this must be read before it is renamed
  await api("/Startup/User", { method: "POST", body: { Name: "vela", Password: "vela" } });
  await api("/Startup/RemoteAccess", { method: "POST", body: { EnableRemoteAccess: true, EnableAutomaticPortMapping: false } });
  await api("/Startup/Complete", { method: "POST" });
}

async function main(): Promise<void> {
  makeVideo();
  await waitFor("Jellyfin to answer", async () => ((await fetch(`${URL_BASE}/health`)).ok ? true : undefined), 180_000);
  await wizard();

  const auth = (await (await api("/Users/AuthenticateByName", { method: "POST", body: { Username: "vela", Pw: "vela" } })).json()) as { AccessToken: string; User: { Id: string } };
  const token = auth.AccessToken;
  const userId = auth.User.Id;

  // Short items are not resumable under the default minimum of 300 s.
  const conf = (await (await api("/System/Configuration", { token })).json()) as Record<string, unknown>;
  await api("/System/Configuration", { method: "POST", token, body: { ...conf, MinResumeDurationSeconds: 0 } });

  const q = new URLSearchParams({ name: "Movies", collectionType: "movies", refreshLibrary: "true" });
  await api(`/Library/VirtualFolders?${q}`, { method: "POST", token, body: { LibraryOptions: { PathInfos: [{ Path: MEDIA_MOUNT }] } } });

  const item = await waitFor(
    "the library scan to find the video",
    async () => {
      const r = (await (await api(`/Items?${new URLSearchParams({ userId, recursive: "true", includeItemTypes: "Movie", fields: "MediaSources" })}`, { token })).json()) as { Items: { Id: string; RunTimeTicks?: number }[] };
      const it = r.Items[0];
      return it?.RunTimeTicks ? it : undefined;
    },
    240_000,
  );

  const pos = Math.floor((item.RunTimeTicks ?? 0) / 2);
  await api("/Sessions/Playing", { method: "POST", token, body: { ItemId: item.Id, PositionTicks: 0, CanSeek: true, PlayMethod: "DirectPlay" } });
  await api("/Sessions/Playing/Progress", { method: "POST", token, body: { ItemId: item.Id, PositionTicks: pos, IsPaused: true, CanSeek: true, PlayMethod: "DirectPlay" } });
  await api("/Sessions/Playing/Stopped", { method: "POST", token, body: { ItemId: item.Id, PositionTicks: pos } });

  const resumable = async () => {
    const r = (await (await api(`/UserItems/Resume?${new URLSearchParams({ userId, mediaTypes: "Video" })}`, { token })).json()) as { Items: { Id: string }[] };
    return r.Items.some((i) => i.Id === item.Id) ? true : undefined;
  };
  try {
    await waitFor("the item to appear in Resume", resumable, 10_000, 1000);
  } catch {
    // Playback reports can be dropped for a session with no real player; set the position directly.
    await api(`/UserItems/${item.Id}/UserData?${new URLSearchParams({ userId })}`, { method: "POST", token, body: { PlaybackPositionTicks: pos } });
    await waitFor("the item to appear in Resume", resumable, 10_000, 1000);
  }
  // The API key the owner creates under Dashboard -> API Keys (docs/install.md); the daemon uses it as jellyfinToken.
  const keys = (await (await api("/Auth/Keys", { token })).json()) as { Items: { AppName: string }[] };
  if (!keys.Items.some((k) => k.AppName === "Vela")) await api(`/Auth/Keys?${new URLSearchParams({ app: "Vela" })}`, { method: "POST", token });
  console.log(`seeded ${FILE} (${item.Id}) at 50%, API key "Vela"`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
