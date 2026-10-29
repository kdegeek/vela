import dgram from "node:dgram";
import { afterEach, describe, expect, it } from "vitest";
import { discoverJellyfin, normalizeIp } from "./discovery.js";

let server: dgram.Socket | undefined;
afterEach(() => {
  server?.close();
  server = undefined;
});

/** A fake Jellyfin discovery responder on 127.0.0.1 that answers each probe with `answers`. */
async function responder(answers: string[]): Promise<{ port: number; probes: string[] }> {
  const probes: string[] = [];
  const s = dgram.createSocket("udp4");
  server = s;
  s.on("message", (msg, from) => {
    probes.push(msg.toString());
    for (const a of answers) s.send(a, from.port, from.address);
  });
  await new Promise<void>((r) => s.bind(0, "127.0.0.1", r));
  return { port: s.address().port, probes };
}

describe("Jellyfin discovery", () => {
  it("asks 'who is JellyfinServer?' and returns each server once, ignoring junk", async () => {
    const { port, probes } = await responder([
      JSON.stringify({ Address: "http://127.0.0.1:8096/", Id: "abc", Name: "Den", EndpointAddress: null }),
      JSON.stringify({ Address: "http://127.0.0.1:8096", Id: "abc", Name: "Den" }),
      JSON.stringify({ Address: "file:///etc/passwd", Id: "x", Name: "Evil" }),
      JSON.stringify({ Address: "https://[::ffff:127.0.0.1]:8920", Id: "def" }),
      "not json",
    ]);
    const servers = await discoverJellyfin({ address: "127.0.0.1", port, timeoutMs: 300 });
    expect(probes).toEqual(["who is JellyfinServer?"]);
    expect(servers).toEqual([
      { name: "Den", url: "http://127.0.0.1:8096" },
      { name: "[::ffff:7f00:1]:8920", url: "https://[::ffff:7f00:1]:8920" },
    ]);
  });

  it("drops replies pointing at a host other than the sender, and later conflicting replies for the same Id", async () => {
    const { port } = await responder([
      JSON.stringify({ Address: "http://192.168.1.66:8096", Id: "x1", Name: "Phish" }),
      JSON.stringify({ Address: "http://jellyfin.example:8096", Id: "x2", Name: "By name" }),
      JSON.stringify({ Address: "http://127.0.0.1:8096", Id: "abc", Name: "Den" }),
      JSON.stringify({ Address: "http://127.0.0.1:9999", Id: "abc", Name: "Den too" }),
    ]);
    expect(await discoverJellyfin({ address: "127.0.0.1", port, timeoutMs: 300 })).toEqual([{ name: "Den", url: "http://127.0.0.1:8096" }]);
  });

  it("normalises IPv4-mapped IPv6", () => {
    expect(["::ffff:192.168.1.5", "[::FFFF:192.168.1.5]", "[::ffff:c0a8:105]", "192.168.1.5"].map(normalizeIp)).toEqual(Array(4).fill("192.168.1.5"));
    expect(normalizeIp("[FE80::1]")).toBe("fe80::1");
  });

  it("returns nothing when no server answers", async () => {
    const { port } = await responder([]);
    expect(await discoverJellyfin({ address: "127.0.0.1", port, timeoutMs: 100 })).toEqual([]);
  });
});
