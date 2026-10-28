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
}

export interface HomeState {
  apps: AppTile[];
  rows: Row[];
  status: HomeStatus;
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
  | { type: "toast"; message: string };

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
} as const;

export function isMediaKey(key: string): key is `${"jellyfin" | "mock"}:${string}` {
  return /^(jellyfin|mock):.+$/s.test(key);
}
