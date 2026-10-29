import crypto from "node:crypto";
import { normalizeIp } from "./discovery.js";

export const MAX_WRONG = 5;
export const LOCKOUT_MS = 30_000;

export const newPin = (): string => String(crypto.randomInt(0, 1_000_000)).padStart(6, "0");

/** Hashes both sides to a fixed length first, so neither the length nor the digits of a guess change the timing. */
export function pinMatches(guess: string, pin: string): boolean {
  const h = (s: string) => crypto.createHash("sha256").update(s).digest();
  return crypto.timingSafeEqual(h(guess), h(pin));
}

/** busy: the global rate limit is used up, or the source table is full of locked sources; retry in a second. */
export type PinResult = "ok" | "wrong" | "locked" | "busy";

/** Most sources tracked at once; idle and unlocked ones are forgotten first, locked ones never. */
export const MAX_SOURCES = 1024;
/** PIN evaluations per second across all sources (a token bucket of this size, refilled at this rate). */
export const MAX_CHECKS_PER_SEC = 10;

/**
 * The lockout key for a peer address: IPv4 (and IPv4-mapped IPv6) per address, other IPv6 per /64, since one
 * machine can hold a whole /64.
 */
export function sourceKey(ip: string): string {
  const v4 = normalizeIp(ip);
  if (!v4.includes(":")) return v4;
  const x = v4.replace(/%.*$/, "");
  const [head = "", tail] = x.split("::");
  const groups = (part: string) => (part ? part.split(":").flatMap((g) => (g.includes(".") ? ["0", "0"] : [g])) : []);
  const h = groups(head);
  const full = tail === undefined ? h : [...h, ...Array(Math.max(0, 8 - h.length - groups(tail).length)).fill("0"), ...groups(tail)];
  return `${full.slice(0, 4).map((g) => parseInt(g, 16).toString(16)).join(":")}::/64`;
}

interface Source {
  wrong: number;
  lockedUntil: number;
  last: number;
}

/**
 * The setup PIN. Every fifth wrong guess, from any source, gives a new PIN (onChange tells the TV). Five wrong
 * guesses in a row from one source (IP, or IPv6 /64) refuse that source for 30 s; the lockout is per source, so
 * someone else on the LAN can't lock the owner's phone out. Since sources are cheap, at most 10 guesses a second
 * are evaluated in all; past that a guess is refused untested.
 */
export class PinGuard {
  private pin = newPin();
  private wrongSinceNew = 0;
  private sources = new Map<string, Source>();
  private tokens = MAX_CHECKS_PER_SEC;
  private refilled: number;

  constructor(
    private onChange: () => void = () => {},
    private now: () => number = Date.now,
    private maxSources = MAX_SOURCES,
  ) {
    this.refilled = now();
  }

  current(): string {
    return this.pin;
  }

  /** Seconds left in this IP's lockout, 0 when its guesses are accepted. */
  retryAfter(ip: string): number {
    const s = this.sources.get(sourceKey(ip));
    return s ? Math.max(0, Math.ceil((s.lockedUntil - this.now()) / 1000)) : 0;
  }

  /** Drops idle unlocked sources; for a new source, also the stalest unlocked ones past the cap. Never a lockout. */
  private prune(adding: boolean): void {
    const t = this.now();
    for (const [k, s] of this.sources) if (s.lockedUntil <= t && t - s.last > LOCKOUT_MS) this.sources.delete(k);
    if (!adding) return;
    // Map order is insertion order and entries are re-inserted on use, so the first ones are the stalest.
    for (const [k, s] of this.sources) {
      if (this.sources.size < this.maxSources) break;
      if (s.lockedUntil <= t) this.sources.delete(k);
    }
  }

  private takeToken(): boolean {
    const t = this.now();
    this.tokens = Math.min(MAX_CHECKS_PER_SEC, this.tokens + ((t - this.refilled) / 1000) * MAX_CHECKS_PER_SEC);
    this.refilled = t;
    if (this.tokens < 1) return false;
    this.tokens--;
    return true;
  }

  sourceCount(): number {
    return this.sources.size;
  }

  check(guess: string, ip: string): PinResult {
    const key = sourceKey(ip);
    this.prune(!this.sources.has(key));
    if (this.retryAfter(ip) > 0) return "locked";
    if (!this.sources.has(key) && this.sources.size >= this.maxSources) return "busy";
    if (!this.takeToken()) return "busy";
    const t = this.now();
    if (pinMatches(guess, this.pin)) {
      this.sources.delete(key);
      return "ok";
    }
    const s = this.sources.get(key) ?? { wrong: 0, lockedUntil: 0, last: t };
    this.sources.delete(key);
    s.wrong++;
    s.last = t;
    this.sources.set(key, s);
    if (++this.wrongSinceNew >= MAX_WRONG) {
      this.wrongSinceNew = 0;
      this.pin = newPin();
      this.onChange();
    }
    if (s.wrong >= MAX_WRONG) {
      s.wrong = 0;
      s.lockedUntil = t + LOCKOUT_MS;
      return "locked";
    }
    return "wrong";
  }
}
