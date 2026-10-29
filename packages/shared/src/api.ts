export type AppStatus = "ready" | "installing" | "missing" | "running";

export interface HeroItem {
  title: string;
  year?: number;
  runtimeMin?: number;
  tags: string[];
  blurb?: string;
  backdropUrl: string;
  kicker?: string;
  label?: string;
  sourceId: string;
  progress?: number;
  remainingMin?: number;
  /** oklch() string if known */
  tint?: string;
}

/** key: "jellyfin:<itemId>" | "mock:<slug>" */
export interface MediaCard extends HeroItem {
  key: string;
  posterUrl?: string;
}

export interface Row {
  id: string;
  title: string;
  layout: "wide" | "poster";
  items: MediaCard[];
}

export interface AppTile {
  id: string;
  name: string;
  /** A data: URL of the glyph SVG (inlined by the daemon); /api/glyphs/<file> only as a fallback. */
  glyphUrl: string;
  brandColor: string;
  status: AppStatus;
  feature: HeroItem | null;
}

export interface HomeStatus {
  line?: string;
  insideTemp?: string;
  /** From config ui.clock24h. */
  clock24h?: boolean;
  /** From config ui.effects: "auto" lets a perf run decide between full and lite. */
  effects?: "auto" | "full" | "lite";
}

export interface HomeState {
  apps: AppTile[];
  rows: Row[];
  status: HomeStatus;
  /** Present while the box is in setup mode (no vela.toml yet, or `vela-ctl setup`). */
  setup?: SetupStatus;
}

/** media: pick Jellyfin or skip; ssh: add keys or skip; done: ready to finish; committing: finished, waiting for root to install the files. */
export type SetupStep = "media" | "ssh" | "done" | "committing";

/** What the TV shows during setup. */
export interface SetupStatus {
  /** Left out when the main API listens beyond loopback (config server.listen), where others could read it. */
  pin?: string;
  /** http://<box ip>:7301/ (the QR code adds `#pin=<pin>`); null while the box has no IPv4 address. */
  url: string | null;
  step: SetupStep;
  /** The Jellyfin server's name once sign-in worked. */
  jellyfinServer?: string;
  keyCount: number;
  /**
   * Setup was never finished and its 2-hour window closed (root left /run/vela-setup/expired). The listener is off,
   * there is no PIN or URL, and the TV says how to open setup again.
   */
  expired?: true;
}

/** An SSH public key as the phone shows it for confirmation. */
export interface SetupKey {
  /** The authorized_keys line: `<type> <base64>[ <comment>]`. */
  key: string;
  type: string;
  /** `SHA256:<base64, no padding>`, as `ssh-keygen -l` prints it. */
  fingerprint: string;
  comment?: string;
}

/** GET /setup/api/state: the wizard's progress, for the phone. */
export interface SetupProgress {
  step: SetupStep;
  /** null until the media step is answered. */
  media: { provider: "jellyfin"; url: string; server: string } | { provider: "mock" } | null;
  keys: SetupKey[];
  sshSkipped: boolean;
}

export interface JellyfinServer {
  name: string;
  url: string;
}

export interface Device {
  id: string;
  name: string;
  kind: "tv" | "ir";
  commands: string[];
}

export interface PerfReport {
  frames: number;
  p50Ms: number;
  p95Ms: number;
  droppedPct: number;
  effects: "full" | "lite";
  width: number;
  height: number;
}

export type VelaEvent =
  | { type: "home" }
  | { type: "app-state"; id: string; status: AppStatus }
  | { type: "home-state-changed" }
  | { type: "toast"; message: string }
  /** POST /api/perf/arm was called: the launcher samples frame timing and posts a PerfReport. */
  | { type: "perf-arm" }
  /** Setup mode changed (PIN, address or step); null when setup mode ends. */
  | { type: "setup"; setup: SetupStatus | null };

export const API = {
  health: "/api/health",
  home: "/api/home",
  launch: "/api/launch",
  play: "/api/play",
  goHome: "/api/home/go",
  devices: "/api/devices",
  deviceCommand: "/api/devices/:id/:command",
  events: "/api/events",
  perf: "/api/perf",
  perfArm: "/api/perf/arm",
} as const;

/**
 * The phone's API on the setup listener (port 7301, setup mode only). Every call needs `Authorization: Pin <6 digits>`;
 * POSTs need `content-type: application/json` and a JSON body (`{}` when there is nothing to send).
 */
export const SETUP_API = {
  /** GET → SetupProgress */
  state: "/setup/api/state",
  /** GET → { servers: JellyfinServer[] } (2 s UDP discovery) */
  jellyfinDiscover: "/setup/api/jellyfin/discover",
  /** POST { url, username, password } → { server: string } */
  jellyfin: "/setup/api/jellyfin",
  /** POST {} → SetupProgress */
  jellyfinSkip: "/setup/api/jellyfin/skip",
  /** POST { username } → { keys: SetupKey[] } (not staged until posted to ssh/keys) */
  sshGithub: "/setup/api/ssh/github",
  /** POST { keys: string[] } → SetupProgress */
  sshKeys: "/setup/api/ssh/keys",
  /** POST {} → SetupProgress */
  sshSkip: "/setup/api/ssh/skip",
  /** POST {} → SetupProgress (step "committing") */
  finish: "/setup/api/finish",
} as const;

export function isMediaKey(key: string): key is `${"jellyfin" | "mock"}:${string}` {
  return /^(jellyfin|mock):.+$/s.test(key);
}
