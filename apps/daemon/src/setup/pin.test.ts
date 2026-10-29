import { describe, expect, it, vi } from "vitest";

// Wraps the real timingSafeEqual so the test can see the PIN check goes through it.
vi.mock("node:crypto", async (importOriginal) => {
  const real = await importOriginal<typeof import("node:crypto")>();
  const timingSafeEqual = vi.fn(real.timingSafeEqual);
  return { ...real, default: { ...real, timingSafeEqual }, timingSafeEqual };
});

const crypto = (await import("node:crypto")).default;
const { LOCKOUT_MS, MAX_SOURCES, MAX_WRONG, newPin, PinGuard, pinMatches } = await import("./pin.js");

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

  it("five wrong guesses from one IP give a new PIN, tell the TV, and lock that IP (only) out for 30 s", () => {
    let t = 1_000_000;
    const onChange = vi.fn();
    const g = new PinGuard(onChange, () => t);
    const first = g.current();
    for (let i = 1; i < MAX_WRONG; i++) expect(g.check(wrongFor(first), "10.0.0.66")).toBe("wrong");
    expect(onChange).not.toHaveBeenCalled();
    expect(g.check(wrongFor(first), "10.0.0.66")).toBe("locked");
    expect(onChange).toHaveBeenCalledTimes(1);
    const second = g.current();
    // The attacker's IP is refused even with the right PIN; the owner's phone isn't.
    expect(g.check(second, "10.0.0.66")).toBe("locked");
    expect(g.retryAfter("10.0.0.66")).toBe(30);
    expect(g.retryAfter("10.0.0.7")).toBe(0);
    expect(g.check(second, "10.0.0.7")).toBe("ok");
    t += LOCKOUT_MS - 1;
    expect(g.check(second, "10.0.0.66")).toBe("locked");
    t += 1;
    expect(g.check(second, "10.0.0.66")).toBe("ok");
  });

  it("every fifth wrong guess from any mix of IPs gives a new PIN, without locking anyone out", () => {
    const onChange = vi.fn();
    const g = new PinGuard(onChange);
    const first = g.current();
    for (let i = 0; i < MAX_WRONG; i++) expect(g.check(wrongFor(first), `10.0.1.${i}`)).toBe("wrong");
    expect(onChange).toHaveBeenCalledTimes(1);
    for (let i = 0; i < MAX_WRONG; i++) expect(g.retryAfter(`10.0.1.${i}`)).toBe(0);
    // An owner's success doesn't reset the count, so guessers spread over IPs still force a change every 5 tries.
    const pin = g.current();
    for (let i = 0; i < 3; i++) g.check(wrongFor(pin), "10.0.2.1");
    expect(g.check(pin, "10.0.0.7")).toBe("ok");
    g.check(wrongFor(pin), "10.0.2.2");
    g.check(wrongFor(pin), "10.0.2.3");
    expect(onChange).toHaveBeenCalledTimes(2);
  });

  it("a right PIN resets that IP's wrong count", () => {
    const g = new PinGuard();
    for (let round = 0; round < 3; round++) {
      for (let i = 1; i < MAX_WRONG; i++) g.check(wrongFor(g.current()), "10.0.0.7");
      expect(g.check(g.current(), "10.0.0.7")).toBe("ok");
    }
    expect(g.retryAfter("10.0.0.7")).toBe(0);
  });

  it("forgets idle IPs and holds at most MAX_SOURCES", () => {
    let t = 0;
    const g = new PinGuard(() => {}, () => t);
    g.check("x", "10.0.0.1");
    expect(g.sourceCount()).toBe(1);
    t += LOCKOUT_MS + 1;
    g.check("x", "10.0.0.2");
    expect(g.sourceCount()).toBe(1);
    for (let i = 0; i < MAX_SOURCES + 50; i++) g.check("x", `ip${i}`);
    expect(g.sourceCount()).toBe(MAX_SOURCES);
  });
});
