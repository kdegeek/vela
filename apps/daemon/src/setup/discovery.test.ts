import dgram from "node:dgram";
import { afterEach, describe, expect, it } from "vitest";
import { discoverJellyfin } from "./discovery.js";

let server: dgram.Socket | undefined;
afterEach(() => server?.close());

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
      JSON.stringify({ Address: "http://192.168.1.5:8096/", Id: "abc", Name: "Den", EndpointAddress: null }),
      JSON.stringify({ Address: "http://192.168.1.5:8096", Id: "abc", Name: "Den" }),
      JSON.stringify({ Address: "file:///etc/passwd", Id: "x", Name: "Evil" }),
      JSON.stringify({ Address: "https://jf.example:8920", Id: "def" }),
      "not json",
    ]);
    const servers = await discoverJellyfin({ address: "127.0.0.1", port, timeoutMs: 300 });
    expect(probes).toEqual(["who is JellyfinServer?"]);
    expect(servers).toEqual([
      { name: "Den", url: "http://192.168.1.5:8096" },
      { name: "jf.example:8920", url: "https://jf.example:8920" },
    ]);
  });

  it("returns nothing when no server answers", async () => {
    const { port } = await responder([]);
    expect(await discoverJellyfin({ address: "127.0.0.1", port, timeoutMs: 100 })).toEqual([]);
  });
});
