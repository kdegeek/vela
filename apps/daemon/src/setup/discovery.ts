import dgram from "node:dgram";
import type { JellyfinServer } from "@vela/shared";

/**
 * Jellyfin's LAN discovery: broadcast "who is JellyfinServer?" to UDP 7359 and collect the JSON answers
 * ({ Address, Id, Name }) for `timeoutMs`. Never throws; a network error just means no servers found.
 */
export function discoverJellyfin(opts: { address?: string; port?: number; timeoutMs?: number } = {}): Promise<JellyfinServer[]> {
  const { address = "255.255.255.255", port = 7359, timeoutMs = 2000 } = opts;
  return new Promise((resolve) => {
    const found = new Map<string, JellyfinServer>();
    const sock = dgram.createSocket("udp4");
    let done = false;
    const finish = () => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      sock.close();
      resolve([...found.values()]);
    };
    const timer = setTimeout(finish, timeoutMs);
    sock.on("error", (e) => {
      console.error("vela: jellyfin discovery:", e.message);
      finish();
    });
    sock.on("message", (msg) => {
      try {
        const r = JSON.parse(msg.toString("utf8")) as { Address?: unknown; Id?: unknown; Name?: unknown };
        if (typeof r.Address !== "string") return;
        const u = new URL(r.Address);
        if (u.protocol !== "http:" && u.protocol !== "https:") return;
        const url = u.href.replace(/\/+$/, "");
        const name = typeof r.Name === "string" && r.Name.trim() ? r.Name.trim().slice(0, 100) : u.host;
        found.set(typeof r.Id === "string" ? r.Id : url, { name, url });
      } catch {
        // not a Jellyfin answer
      }
    });
    sock.bind(0, () => {
      sock.setBroadcast(true);
      sock.send("who is JellyfinServer?", port, address, (e) => {
        if (e) {
          console.error("vela: jellyfin discovery:", e.message);
          finish();
        }
      });
    });
  });
}
