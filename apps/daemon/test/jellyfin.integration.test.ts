import { describe, expect, it } from "vitest";
import { JellyfinClient, toCard } from "../src/media/jellyfin.js";

// Runs in CI against a seeded jellyfin/jellyfin container (see dev/jellyfin/seed.ts).
describe.skipIf(!process.env.JELLYFIN_URL)("jellyfin integration", () => {
  it("resume returns the seeded item with progress ≈ 0.5", async () => {
    const c = new JellyfinClient({ url: process.env.JELLYFIN_URL! });
    await c.authenticate("vela", "vela");
    const items = await c.resume();
    const sintel = items.find((i) => i.name === "Sintel");
    expect(sintel, `resume items: ${items.map((i) => i.name).join(", ")}`).toBeDefined();
    const card = toCard(sintel!);
    expect(card.progress).toBeCloseTo(0.5, 1);
    expect(sintel!.year).toBe(2010);
  });
});
