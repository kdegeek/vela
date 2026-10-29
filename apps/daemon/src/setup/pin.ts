import crypto from "node:crypto";

export const MAX_WRONG = 5;
export const LOCKOUT_MS = 30_000;

export const newPin = (): string => String(crypto.randomInt(0, 1_000_000)).padStart(6, "0");

/** Hashes both sides to a fixed length first, so neither the length nor the digits of a guess change the timing. */
export function pinMatches(guess: string, pin: string): boolean {
  const h = (s: string) => crypto.createHash("sha256").update(s).digest();
  return crypto.timingSafeEqual(h(guess), h(pin));
}

export type PinResult = "ok" | "wrong" | "locked";

/** Most sources tracked at once; past this the stalest are forgotten. */
export const MAX_SOURCES = 1024;

interface Source {
  wrong: number;
  lockedUntil: number;
  last: number;
}

/**
 * The setup PIN. Every fifth wrong guess, from any source, gives a new PIN (onChange tells the TV): that is what
 * defeats guessing. Five wrong guesses in a row from one IP also refuse that IP for 30 s. The lockout is per IP, so
 * someone else on the LAN can't lock the owner's phone out; it can only make the PIN change.
 */
export class PinGuard {
  private pin = newPin();
  private wrongSinceNew = 0;
  private sources = new Map<string, Source>();

  constructor(
    private onChange: () => void = () => {},
    private now: () => number = Date.now,
  ) {}

  current(): string {
    return this.pin;
  }

  /** Seconds left in this IP's lockout, 0 when its guesses are accepted. */
  retryAfter(ip: string): number {
    const s = this.sources.get(ip);
    return s ? Math.max(0, Math.ceil((s.lockedUntil - this.now()) / 1000)) : 0;
  }

  /** Forgets sources idle for longer than a lockout (their count would have expired anyway), then the stalest past the cap. */
  private prune(): void {
    const t = this.now();
    for (const [ip, s] of this.sources) if (s.lockedUntil <= t && t - s.last > LOCKOUT_MS) this.sources.delete(ip);
    // Map order is insertion order and entries are re-inserted on use, so the first ones are the stalest.
    for (const ip of this.sources.keys()) {
      if (this.sources.size < MAX_SOURCES) break; // room for the one check() may add
      this.sources.delete(ip);
    }
  }

  sourceCount(): number {
    return this.sources.size;
  }

  check(guess: string, ip: string): PinResult {
    this.prune();
    if (this.retryAfter(ip) > 0) return "locked";
    const t = this.now();
    if (pinMatches(guess, this.pin)) {
      this.sources.delete(ip);
      return "ok";
    }
    const s = this.sources.get(ip) ?? { wrong: 0, lockedUntil: 0, last: t };
    this.sources.delete(ip);
    s.wrong++;
    s.last = t;
    this.sources.set(ip, s);
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
