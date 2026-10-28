import fs from "node:fs";
import type { MediaCard } from "@vela/shared";
import { saveSecrets, type Secrets } from "../config.js";

export interface JellyfinItem {
  id: string;
  name: string;
  year?: number;
  runTimeTicks?: number;
  positionTicks?: number;
  genres: string[];
  overview?: string;
  hasBackdrop: boolean;
  hasPrimary: boolean;
  container?: string;
}

export const ART_WIDTHS = [3840, 1280, 704] as const;
const TICKS_PER_MIN = 600_000_000;

function machineId(): string {
  try {
    return fs.readFileSync("/etc/machine-id", "utf8").trim() || "vela";
  } catch {
    return "vela";
  }
}

interface RawItem {
  Id: string;
  Name: string;
  ProductionYear?: number;
  RunTimeTicks?: number;
  UserData?: { PlaybackPositionTicks?: number };
  Genres?: string[];
  Overview?: string;
  BackdropImageTags?: string[];
  ImageTags?: { Primary?: string };
  Container?: string;
}

export class JellyfinClient {
  private url: string;
  private token?: string;
  private userId?: string;
  private deviceId: string;
  private f: typeof fetch;
  /** Image tags seen in listings, used to make upstream image URLs cache-correct. */
  private tags = new Map<string, string>();

  constructor(opts: { url: string; token?: string; userId?: string; fetch?: typeof fetch; deviceId?: string }) {
    this.url = opts.url.replace(/\/+$/, "");
    this.token = opts.token;
    this.userId = opts.userId;
    this.f = opts.fetch ?? fetch;
    this.deviceId = opts.deviceId ?? machineId();
  }

  /** Header for every upstream request, including the token when we have one. */
  authHeader(): string {
    const base = `MediaBrowser Client="Vela", Device="Vela", DeviceId="${this.deviceId}", Version="0.1.0"`;
    return this.token ? `${base}, Token="${this.token}"` : base;
  }

  private headers(json = false): Record<string, string> {
    return { authorization: this.authHeader(), ...(json ? { "content-type": "application/json" } : {}) };
  }

  private async req(path: string, init: RequestInit = {}): Promise<Response> {
    const res = await this.f(`${this.url}${path}`, { signal: AbortSignal.timeout(10_000), ...init });
    if (!res.ok) throw new Error(`Jellyfin ${init.method ?? "GET"} ${path.split("?")[0]} failed: ${res.status}`);
    return res;
  }

  async authenticate(username: string, password: string): Promise<{ token: string; userId: string }> {
    const res = await this.req("/Users/AuthenticateByName", {
      method: "POST",
      headers: this.headers(true),
      body: JSON.stringify({ Username: username, Pw: password }),
    });
    const j = (await res.json()) as { AccessToken: string; User: { Id: string } };
    this.token = j.AccessToken;
    this.userId = j.User.Id;
    return { token: j.AccessToken, userId: j.User.Id };
  }

  async resume(limit = 12): Promise<JellyfinItem[]> {
    if (!this.userId) throw new Error("Jellyfin client has no user id");
    const q = new URLSearchParams({ userId: this.userId, limit: String(limit), mediaTypes: "Video", fields: "Overview,Genres", enableImageTypes: "Backdrop,Primary" });
    const res = await this.req(`/UserItems/Resume?${q}`, { headers: this.headers() });
    const j = (await res.json()) as { Items?: RawItem[] };
    return (j.Items ?? []).map((r) => {
      const bd = r.BackdropImageTags?.[0];
      const pr = r.ImageTags?.Primary;
      if (bd) this.tags.set(`${r.Id}/Backdrop`, bd);
      if (pr) this.tags.set(`${r.Id}/Primary`, pr);
      return {
        id: r.Id,
        name: r.Name,
        year: r.ProductionYear,
        runTimeTicks: r.RunTimeTicks,
        positionTicks: r.UserData?.PlaybackPositionTicks,
        genres: r.Genres ?? [],
        overview: r.Overview,
        hasBackdrop: !!bd,
        hasPrimary: !!pr,
        container: r.Container,
      };
    });
  }

  imageUrl(itemId: string, type: "Backdrop" | "Primary", maxWidth: number): string {
    const q = new URLSearchParams({ maxWidth: String(maxWidth), quality: "90" });
    const tag = this.tags.get(`${itemId}/${type}`);
    if (tag) q.set("tag", tag);
    const suffix = type === "Backdrop" ? "/0" : "";
    return `${this.url}/Items/${encodeURIComponent(itemId)}/Images/${type}${suffix}?${q}`;
  }

  /** Fetches upstream image bytes with the token. */
  fetchImage(itemId: string, type: "Backdrop" | "Primary", maxWidth: number): Promise<Response> {
    return this.f(this.imageUrl(itemId, type, maxWidth), { headers: this.headers(), signal: AbortSignal.timeout(15_000) });
  }

  streamUrl(itemId: string): string {
    return `${this.url}/Videos/${encodeURIComponent(itemId)}/stream?static=true&api_key=${encodeURIComponent(this.token ?? "")}`;
  }

  private async report(path: string, body: Record<string, unknown>): Promise<void> {
    await this.req(path, { method: "POST", headers: this.headers(true), body: JSON.stringify(body) });
  }

  reportStart(itemId: string, positionTicks: number): Promise<void> {
    return this.report("/Sessions/Playing", { ItemId: itemId, PositionTicks: positionTicks, CanSeek: true, PlayMethod: "DirectPlay" });
  }

  reportProgress(itemId: string, positionTicks: number, paused: boolean): Promise<void> {
    return this.report("/Sessions/Playing/Progress", { ItemId: itemId, PositionTicks: positionTicks, IsPaused: paused, CanSeek: true, PlayMethod: "DirectPlay" });
  }

  reportStopped(itemId: string, positionTicks: number): Promise<void> {
    return this.report("/Sessions/Playing/Stopped", { ItemId: itemId, PositionTicks: positionTicks });
  }
}

export function toCard(item: JellyfinItem): MediaCard {
  const run = item.runTimeTicks;
  const pos = item.positionTicks;
  const hasRun = run !== undefined && run > 0;
  const hasPos = hasRun && pos !== undefined && pos > 0;
  const art = (type: string) => `/api/art/jellyfin/${item.id}/${type}?w=`;
  return {
    key: `jellyfin:${item.id}`,
    title: item.name,
    year: item.year,
    runtimeMin: hasRun ? Math.round(run / TICKS_PER_MIN) : undefined,
    tags: item.genres.slice(0, 3),
    blurb: item.overview,
    backdropUrl: `${art(item.hasBackdrop || !item.hasPrimary ? "Backdrop" : "Primary")}3840`,
    posterUrl: item.hasPrimary ? `${art("Primary")}704` : undefined,
    kicker: "Continue watching",
    sourceId: "jellyfin",
    progress: hasPos ? Math.min(1, pos / run!) : undefined,
    remainingMin: hasPos ? Math.max(0, Math.ceil((run! - pos) / TICKS_PER_MIN)) : undefined,
  };
}

/** Logs in with a password and stores the token and user id; the password itself is never stored. */
export async function loginAndSave(client: JellyfinClient, username: string, password: string, secretsPath: string, current: Secrets): Promise<Secrets> {
  const { token, userId } = await client.authenticate(username, password);
  const next = { ...current, jellyfinToken: token, jellyfinUserId: userId };
  saveSecrets(secretsPath, next);
  return next;
}
