import { describe, expect, it, vi } from "vitest";

// Wraps the real timingSafeEqual so the test can see the PIN check goes through it.
vi.mock("node:crypto", async (importOriginal) => {
  const real = await importOriginal<typeof import("node:crypto")>();
  const timingSafeEqual = vi.fn(real.timingSafeEqual);
  return { ...real, default: { ...real, timingSafeEqual }, timingSafeEqual };
});

const crypto = (await import("node:crypto")).default;
const { LOCKOUT_MS, MAX_WRONG, newPin, PinGuard, pinMatches } = await import("./pin.js");

const wrongFor = (pin: string) => (pin === "000000" ? "111111" : "000000");

describe("setup PIN", () => {
  it("is six digits", () => {
    for (let i = 0; i < 200; i++) expect(newPin()).toMatch(/^\d{6}$/);
  });

  it("compares in constant time, whatever the guess's length", () => {
    const spy = vi.mocked(crypto.timingSafeEqual);
    spy.mockClear();
    expect(pinMatches("123456", "123456")).toBe(true);
    expect(pinMatches("123457", "123456")).toBe(false);
    expect(pinMatches("1", "123456")).toBe(false);
    expect(pinMatches("1234567890", "123456")).toBe(false);
    expect(spy).toHaveBeenCalledTimes(4);
    for (const [a, b] of spy.mock.calls) expect((a as Buffer).length).toBe((b as Buffer).length);
  });

  it("five wrong guesses give a new PIN, tell the TV, and refuse every guess for 30 s", () => {
    let t = 1_000_000;
    const onChange = vi.fn();
    const g = new PinGuard(onChange, () => t);
    const first = g.current();
    for (let i = 1; i < MAX_WRONG; i++) expect(g.check(wrongFor(first))).toBe("wrong");
    expect(onChange).not.toHaveBeenCalled();
    expect(g.check(wrongFor(first))).toBe("locked");
    expect(onChange).toHaveBeenCalledTimes(1);
    const second = g.current();
    // Even the right (new) PIN is refused during the delay, so guessing gets nowhere.
    expect(g.check(second)).toBe("locked");
    expect(g.retryAfter()).toBe(30);
    t += LOCKOUT_MS - 1;
    expect(g.check(second)).toBe("locked");
    t += 1;
    expect(g.check(second)).toBe("ok");
    expect(g.retryAfter()).toBe(0);
  });

  it("a right PIN resets the wrong count", () => {
    const g = new PinGuard();
    const pin = g.current();
    for (let i = 1; i < MAX_WRONG; i++) g.check(wrongFor(pin));
    expect(g.check(pin)).toBe("ok");
    for (let i = 1; i < MAX_WRONG; i++) expect(g.check(wrongFor(pin))).toBe("wrong");
    expect(g.current()).toBe(pin);
  });
});
