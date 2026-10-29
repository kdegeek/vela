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

/** The setup PIN. Five wrong guesses in a row give a new PIN (onChange tells the TV) and refuse every guess for 30 s. */
export class PinGuard {
  private pin = newPin();
  private wrong = 0;
  private lockedUntil = 0;

  constructor(
    private onChange: () => void = () => {},
    private now: () => number = Date.now,
  ) {}

  current(): string {
    return this.pin;
  }

  /** Seconds left in a lockout, 0 when guesses are accepted. */
  retryAfter(): number {
    return Math.max(0, Math.ceil((this.lockedUntil - this.now()) / 1000));
  }

  check(guess: string): PinResult {
    if (this.retryAfter() > 0) return "locked";
    if (pinMatches(guess, this.pin)) {
      this.wrong = 0;
      return "ok";
    }
    if (++this.wrong >= MAX_WRONG) {
      this.wrong = 0;
      this.pin = newPin();
      this.lockedUntil = this.now() + LOCKOUT_MS;
      this.onChange();
      return "locked";
    }
    return "wrong";
  }
}
