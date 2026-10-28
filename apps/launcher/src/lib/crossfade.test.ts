import { describe, expect, it, vi } from "vitest";
import { createCrossfadeQueue } from "./crossfade.js";

describe("createCrossfadeQueue", () => {
  it("swaps immediately when idle", () => {
    const swap = vi.fn();
    const q = createCrossfadeQueue(swap);
    q.request("a");
    expect(swap).toHaveBeenCalledWith("a");
  });

  it("settle during a running fade queues, does not swap", () => {
    const swap = vi.fn();
    const q = createCrossfadeQueue(swap);
    q.request("a");
    q.request("b");
    expect(swap).toHaveBeenCalledTimes(1);
    expect(q.fading).toBe(true);
  });

  it("only the latest pending is shown after the fade ends", () => {
    const swap = vi.fn();
    const q = createCrossfadeQueue(swap);
    q.request("a");
    q.request("b");
    q.request("c");
    q.done();
    expect(swap.mock.calls.map((c) => c[0])).toEqual(["a", "c"]);
    q.done();
    expect(swap).toHaveBeenCalledTimes(2);
    expect(q.fading).toBe(false);
  });

  it("a pending request equal to what is showing does not refade", () => {
    const swap = vi.fn();
    const q = createCrossfadeQueue(swap);
    q.request("a");
    q.request("b");
    q.request("a");
    q.done();
    expect(swap).toHaveBeenCalledTimes(1);
    expect(q.fading).toBe(false);
  });
});
