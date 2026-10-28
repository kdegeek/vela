import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createHeroSettler } from "./hero.js";

describe("createHeroSettler", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("settles once after a burst of moves", () => {
    const apply = vi.fn();
    const s = createHeroSettler<number>(apply);
    for (let i = 0; i < 10; i++) {
      s.focus(i);
      vi.advanceTimersByTime(30);
    }
    expect(apply).not.toHaveBeenCalled();
    vi.advanceTimersByTime(120);
    expect(apply).toHaveBeenCalledTimes(1);
    expect(apply).toHaveBeenCalledWith(9);
  });

  it("dispose cancels a pending apply", () => {
    const apply = vi.fn();
    const s = createHeroSettler<string>(apply, 50);
    s.focus("a");
    s.dispose();
    vi.advanceTimersByTime(500);
    expect(apply).not.toHaveBeenCalled();
  });
});
