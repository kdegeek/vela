import { describe, expect, it } from "vitest";
import { JellyfinClient, toCard } from "../src/media/jellyfin.js";

// Runs in CI against a seeded jellyfin/jellyfin container (see dev/jellyfin/seed.ts), which also
// creates the "Vela" API key that docs/install.md tells the owner to make.
const URL_ = process.env.JELLYFIN_URL!;
const TICKS_PER_SEC = 10_000_000;

/** What the daemon is configured with: the API key and the user id, as in secrets.json. */
async function daemonClient(): Promise<{ client: JellyfinClient; userId: string }> {
  const admin = new JellyfinClient({ url: URL_ });
  const { token, userId } = await admin.authenticate("vela", "vela");
  const res = await fetch(`${URL_}/Auth/Keys`, { headers: { authorization: `MediaBrowser Client="Vela test", Device="ci", DeviceId="ci", Version="0", Token="${token}"` } });
  expect(res.ok).toBe(true);
  const keys = ((await res.json()) as { Items: { AccessToken: string; AppName: string }[] }).Items;
  const key = keys.find((k) => k.AppName === "Vela")?.AccessToken;
  expect(key, "the seed creates an API key named Vela").toBeDefined();
  return { client: new JellyfinClient({ url: URL_, token: key, userId, deviceId: "vela-ci" }), userId };
}

describe.skipIf(!process.env.JELLYFIN_URL)("jellyfin integration", () => {
  it("resume returns the seeded item with progress ≈ 0.5", async () => {
    const c = new JellyfinClient({ url: URL_ });
    await c.authenticate("vela", "vela");
    const items = await c.resume();
    const sintel = items.find((i) => i.name === "Sintel");
    expect(sintel, `resume items: ${items.map((i) => i.name).join(", ")}`).toBeDefined();
    const card = toCard(sintel!);
    expect(card.progress).toBeCloseTo(0.5, 1);
    expect(sintel!.year).toBe(2010);
  });

  it("the daemon's reporter (API key + user id) moves the resume position", async () => {
    const { client } = await daemonClient();
    const before = (await client.resume()).find((i) => i.name === "Sintel");
    expect(before, "the API key client sees the resume list").toBeDefined();
    const run = before!.runTimeTicks!;
    const target = Math.round(run * 0.3);
    // The same calls, in the same order, as playJellyfin.
    await client.reportStart(before!.id, before!.positionTicks ?? 0);
    await client.reportProgress(before!.id, Math.round(run * 0.2), false);
    await client.reportStopped(before!.id, target);
    let after: number | undefined;
    for (let i = 0; i < 10; i++) {
      after = (await client.resume()).find((x) => x.id === before!.id)?.positionTicks;
      if (after !== undefined && Math.abs(after - target) < TICKS_PER_SEC) break;
      await new Promise((r) => setTimeout(r, 1000));
    }
    expect(after, `resume position after reportStopped (target ${target}, was ${before!.positionTicks})`).toBeDefined();
    expect(Math.abs(after! - target)).toBeLessThan(TICKS_PER_SEC);
  });
});
