import dgram from "node:dgram";
import type { JellyfinServer } from "@vela/shared";

/** IPv4-mapped IPv6 ("::ffff:192.168.1.5", or "[::ffff:c0a8:105]" as URL parses it) → "192.168.1.5"; IPv6 lowercased without brackets. */
export function normalizeIp(a: string): string {
  const x = a.replace(/^\[|\]$/g, "").toLowerCase();
  if (x.startsWith("::ffff:") && x.slice(7).includes(".")) return x.slice(7);
  const hex = /^::ffff:([0-9a-f]{1,4}):([0-9a-f]{1,4})$/.exec(x);
  if (hex) {
    const [hi, lo] = [parseInt(hex[1]!, 16), parseInt(hex[2]!, 16)];
    return `${hi >> 8}.${hi & 255}.${lo >> 8}.${lo & 255}`;
  }
  return x;
}

/**
 * Jellyfin's LAN discovery: broadcast "who is JellyfinServer?" to UDP 7359 and collect the JSON answers
 * ({ Address, Id, Name }) for `timeoutMs`. Never throws; a network error just means no servers found.
 * Anyone on the LAN can answer, so a reply counts only if it came from the host its Address names (a reply pointing
 * the phone at some other machine, to collect the password, is dropped), and the first reply per Id wins.
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
    sock.on("message", (msg, from) => {
      try {
        const r = JSON.parse(msg.toString("utf8")) as { Address?: unknown; Id?: unknown; Name?: unknown };
        if (typeof r.Address !== "string") return;
        const u = new URL(r.Address);
        if (u.protocol !== "http:" && u.protocol !== "https:") return;
        if (normalizeIp(u.hostname) !== normalizeIp(from.address)) return;
        const url = u.href.replace(/\/+$/, "");
        const name = typeof r.Name === "string" && r.Name.trim() ? r.Name.trim().slice(0, 100) : u.host;
        const id = typeof r.Id === "string" ? r.Id : url;
        if (!found.has(id)) found.set(id, { name, url });
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
