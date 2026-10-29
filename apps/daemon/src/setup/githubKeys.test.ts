import { describe, expect, it, vi } from "vitest";
import { fetchGithubKeys, KeyError, MAX_KEYS, parseKeyLine, parseKeys } from "./githubKeys.js";

// Generated with ssh-keygen; fingerprints are what `ssh-keygen -lf` printed.
const ED25519 = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIM3a/gFsAyb6ZO/J0a6ccNAsC6JZsMf3aIWNvcWfZgU9";
const ED25519_FP = "SHA256:S9mp+UD5gdC3LzRIgablt17qpD3KLJatMvHQUZCEW7I";
const ECDSA =
  "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBPjIMXKngOy0+vEYWyMyAt4k2CNEWr/1Fkulp+oQ4h9Hz31g3r/cAYf5teIpbF5H6AVXsQRmMtk1xA+Bkj7MwZc=";
const ECDSA_FP = "SHA256:OZp8gAL0WuI32pHJy4j0TpoSBWHpqV9nfYHclY0JWz8";

/** A fresh, valid ed25519 key line: the blob is just the type string plus 32 bytes. */
function fakeEd25519(i: number): string {
  const t = Buffer.from("ssh-ed25519");
  const blob = Buffer.concat([Buffer.from([0, 0, 0, t.length]), t, Buffer.from([0, 0, 0, 32]), Buffer.alloc(32, i)]);
  return `ssh-ed25519 ${blob.toString("base64")}`;
}

describe("SSH key parsing", () => {
  it("returns type and the SHA256 fingerprint ssh-keygen prints", () => {
    expect(parseKeyLine(`${ED25519} test@vela`)).toEqual({ key: `${ED25519} test@vela`, type: "ssh-ed25519", fingerprint: ED25519_FP, comment: "test@vela" });
    expect(parseKeyLine(`  ${ECDSA}  `)).toEqual({ key: ECDSA, type: "ecdsa-sha2-nistp256", fingerprint: ECDSA_FP });
  });

  it("refuses option prefixes", () => {
    for (const l of [`command="/bin/sh" ${ED25519}`, `no-pty ${ED25519}`, `from="10.0.0.1",no-agent-forwarding ${ED25519} x`]) {
      expect(() => parseKeyLine(l)).toThrow(/options/);
    }
  });

  it("refuses things that aren't keys, damaged keys, and a type that doesn't match the blob", () => {
    expect(() => parseKeyLine("hello world")).toThrow(KeyError);
    expect(() => parseKeyLine("ssh-ed25519")).toThrow(/damaged/);
    expect(() => parseKeyLine("ssh-ed25519 AAAA!!!")).toThrow(/damaged/);
    expect(() => parseKeyLine(`ssh-rsa ${ED25519.split(" ")[1]}`)).toThrow(/damaged/);
    expect(() => parseKeyLine(`${ED25519.slice(0, 40)}`)).toThrow(/damaged/);
    expect(() => parseKeyLine(`${ED25519} bad\u0000comment`)).toThrow(KeyError);
  });

  it("drops blank lines and repeats; more than 16 keys is refused", () => {
    expect(parseKeys(["", ED25519, `${ED25519} again`, ECDSA]).map((k) => k.fingerprint)).toEqual([ED25519_FP, ECDSA_FP]);
    const many = Array.from({ length: MAX_KEYS + 1 }, (_, i) => fakeEd25519(i));
    expect(parseKeys(many.slice(0, MAX_KEYS))).toHaveLength(MAX_KEYS);
    expect(() => parseKeys(many)).toThrow(/at most 16/);
  });
});

describe("GitHub keys", () => {
  const ok = (body: string) => vi.fn(async (_u: string | URL | Request, _i?: RequestInit) => new Response(body, { status: 200 }));

  it("fetches only https://github.com/<user>.keys, without following redirects, and labels the keys", async () => {
    const f = ok(`${ED25519}\n${ECDSA}\n`);
    const keys = await fetchGithubKeys("octo-cat", f as unknown as typeof fetch);
    expect(f).toHaveBeenCalledTimes(1);
    expect(f.mock.calls[0]![0]).toBe("https://github.com/octo-cat.keys");
    expect(f.mock.calls[0]![1]!.redirect).toBe("error");
    expect(keys.map((k) => [k.type, k.fingerprint, k.comment])).toEqual([
      ["ssh-ed25519", ED25519_FP, "octo-cat@github"],
      ["ecdsa-sha2-nistp256", ECDSA_FP, "octo-cat@github"],
    ]);
  });

  it("validates the username before any request", async () => {
    const f = ok(ED25519);
    for (const u of ["", "a/b", "../x", "x.keys?", "a b", "a".repeat(40), "evil.com#", "user@host"]) {
      await expect(fetchGithubKeys(u, f as unknown as typeof fetch)).rejects.toThrow(/GitHub username/);
    }
    expect(f).not.toHaveBeenCalled();
    await expect(fetchGithubKeys("a".repeat(39), f as unknown as typeof fetch)).resolves.toHaveLength(1);
  });

  it("says what happened: no user, no keys, GitHub down, unreachable", async () => {
    const r = (status: number, body = "") => (async () => new Response(body, { status })) as unknown as typeof fetch;
    await expect(fetchGithubKeys("nobody", r(404))).rejects.toThrow(/no user named nobody/);
    await expect(fetchGithubKeys("empty", r(200, "\n"))).rejects.toThrow(/no SSH keys on GitHub/);
    await expect(fetchGithubKeys("x", r(503))).rejects.toThrow(/error \(503\)/);
    await expect(fetchGithubKeys("huge", r(200, `${ED25519}\n`.repeat(2000)))).rejects.toThrow(/more keys than Vela can take/);
    const down = (async () => {
      throw new TypeError("fetch failed");
    }) as unknown as typeof fetch;
    await expect(fetchGithubKeys("x", down)).rejects.toThrow(/Couldn't reach GitHub/);
  });
});
