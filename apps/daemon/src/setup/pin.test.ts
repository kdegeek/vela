import { describe, expect, it, vi } from "vitest";

// Wraps the real timingSafeEqual so the test can see the PIN check goes through it.
vi.mock("node:crypto", async (importOriginal) => {
  const real = await importOriginal<typeof import("node:crypto")>();
  const timingSafeEqual = vi.fn(real.timingSafeEqual);
  return { ...real, default: { ...real, timingSafeEqual }, timingSafeEqual };
});

const crypto = (await import("node:crypto")).default;
const { LOCKOUT_MS, MAX_CHECKS_PER_SEC, MAX_WRONG, newPin, PinGuard, pinMatches, sourceKey } = await import("./pin.js");

/** A clock that moves `step` ms on every read, so the rate limit never gets in the way of a test about something else. */
const ticking = (step = 100) => {
  let t = 0;
  return () => (t += step);
};

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
    const g = new PinGuard(onChange, ticking());
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
    const g = new PinGuard(() => {}, ticking());
    for (let round = 0; round < 3; round++) {
      for (let i = 1; i < MAX_WRONG; i++) g.check(wrongFor(g.current()), "10.0.0.7");
      expect(g.check(g.current(), "10.0.0.7")).toBe("ok");
    }
    expect(g.retryAfter("10.0.0.7")).toBe(0);
  });

  it("forgets idle IPs", () => {
    let t = 0;
    const g = new PinGuard(() => {}, () => t);
    g.check("x", "10.0.0.1");
    expect(g.sourceCount()).toBe(1);
    t += LOCKOUT_MS + 1;
    g.check("x", "10.0.0.2");
    expect(g.sourceCount()).toBe(1);
  });

  it("evaluates at most 10 guesses a second in all; the rest are refused untested", () => {
    let t = 0;
    const onChange = vi.fn();
    const g = new PinGuard(onChange, () => t);
    const pin = g.current();
    const spy = vi.mocked(crypto.timingSafeEqual);
    spy.mockClear();
    // Spread over IPs, so no lockout: only the rate limit stops them.
    for (let i = 0; i < MAX_CHECKS_PER_SEC; i++) expect(g.check(i === 0 ? pin : wrongFor(pin), `10.1.0.${i}`)).not.toBe("busy");
    expect(spy).toHaveBeenCalledTimes(MAX_CHECKS_PER_SEC);
    const changes = onChange.mock.calls.length;
    for (let i = 0; i < 50; i++) expect(g.check(wrongFor(g.current()), `10.2.0.${i}`)).toBe("busy");
    expect(g.check(g.current(), "10.0.0.7")).toBe("busy");
    expect(spy).toHaveBeenCalledTimes(MAX_CHECKS_PER_SEC);
    expect(onChange).toHaveBeenCalledTimes(changes);
    t += 1000 / MAX_CHECKS_PER_SEC;
    expect(g.check(g.current(), "10.0.0.7")).toBe("ok");
    expect(g.check(g.current(), "10.0.0.7")).toBe("busy");
  });

  it("never forgets a locked-out source to make room; a new source waits instead", () => {
    const now = ticking();
    const g = new PinGuard(() => {}, now, 3);
    const lock = (ip: string) => {
      for (let i = 0; i < MAX_WRONG; i++) g.check(wrongFor(g.current()), ip);
      expect(g.retryAfter(ip)).toBeGreaterThan(0);
    };
    lock("10.0.0.1");
    lock("10.0.0.2");
    g.check(wrongFor(g.current()), "10.0.0.3"); // unlocked: may be forgotten
    g.check(wrongFor(g.current()), "10.0.0.4");
    expect(g.sourceCount()).toBe(3);
    expect(g.retryAfter("10.0.0.1")).toBeGreaterThan(0);
    expect(g.retryAfter("10.0.0.2")).toBeGreaterThan(0);
    lock("10.0.0.4");
    // Full of lockouts: a new source is refused rather than one being dropped.
    expect(g.check(g.current(), "10.0.0.5")).toBe("busy");
    for (const ip of ["10.0.0.1", "10.0.0.2"]) expect(g.check(g.current(), ip)).toBe("locked");
    expect(g.sourceCount()).toBe(3);
  });

  it("groups IPv6 by /64 and keeps IPv4 (and IPv4-mapped) per address", () => {
    expect(sourceKey("192.168.1.5")).toBe("192.168.1.5");
    expect(sourceKey("::ffff:192.168.1.5")).toBe("192.168.1.5");
    expect(sourceKey("2001:db8:1:2:aaaa::1")).toBe("2001:db8:1:2::/64");
    expect(sourceKey("2001:0db8:0001:0002:ffff:ffff:ffff:ffff")).toBe("2001:db8:1:2::/64");
    expect(sourceKey("2001:db8::1")).toBe("2001:db8:0:0::/64");
    expect(sourceKey("fe80::1%en0")).toBe("fe80:0:0:0::/64");
    expect(sourceKey("::1")).toBe("0:0:0:0::/64");

    const g = new PinGuard(() => {}, ticking());
    for (let i = 0; i < MAX_WRONG; i++) g.check(wrongFor(g.current()), `2001:db8:1:2::${i + 1}`);
    expect(g.check(g.current(), "2001:db8:1:2:ffff::9")).toBe("locked");
    expect(g.check(g.current(), "2001:db8:1:3::1")).toBe("ok");
  });
});
