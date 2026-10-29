import crypto from "node:crypto";
import type { SetupKey } from "@vela/shared";

export const MAX_KEYS = 16;
export const GITHUB_USER = /^[A-Za-z0-9-]{1,39}$/;

/** Key types, with how many length-prefixed fields their blob holds (type string included). */
const KEY_TYPES = new Map([
  ["ssh-ed25519", 2],
  ["ssh-rsa", 3],
  ["ecdsa-sha2-nistp256", 3],
  ["ecdsa-sha2-nistp384", 3],
  ["ecdsa-sha2-nistp521", 3],
  ["sk-ssh-ed25519@openssh.com", 3],
  ["sk-ecdsa-sha2-nistp256@openssh.com", 4],
]);
const BASE64 = /^[A-Za-z0-9+/]+={0,2}$/;
const MAX_LINE = 8192;
const MAX_BODY = 64 * 1024;

/** A key the box won't accept; the message is shown on the phone as is. */
export class KeyError extends Error {}

/** `SHA256:<base64 of sha256(blob), no padding>`, the same string `ssh-keygen -l` prints. */
export function fingerprint(blob: Buffer): string {
  return `SHA256:${crypto.createHash("sha256").update(blob).digest("base64").replace(/=+$/, "")}`;
}

/**
 * The blob is a run of length-prefixed fields starting with its own type. Checking the type and that the fields fill
 * the blob exactly catches truncated or mangled pastes (setup-commit's `ssh-keygen -l` checks the key itself).
 */
function blobFits(blob: Buffer, type: string): boolean {
  const fields: Buffer[] = [];
  let at = 0;
  while (at < blob.length) {
    if (at + 4 > blob.length) return false;
    const n = blob.readUInt32BE(at);
    if (at + 4 + n > blob.length) return false;
    fields.push(blob.subarray(at + 4, at + 4 + n));
    at += 4 + n;
  }
  return fields.length === KEY_TYPES.get(type) && fields[0]!.toString("latin1") === type;
}

/** One authorized_keys line, plain (`<type> <base64> [comment]`): option prefixes such as `command="…"` are refused. */
export function parseKeyLine(line: string): SetupKey {
  const text = line.trim();
  if (text.length > MAX_LINE) throw new KeyError("That key is too long to be an SSH public key.");
  if (/[\r\n]/.test(text)) throw new KeyError("Put each key on its own line.");
  const [type = "", b64 = "", ...rest] = text.split(/[ \t]+/);
  if (!KEY_TYPES.has(type)) {
    if (text.split(/[ \t]+/).some((w) => KEY_TYPES.has(w))) {
      throw new KeyError("Key options (like command=\"…\") aren't allowed. Paste just the key, starting with its type.");
    }
    throw new KeyError("That isn't an SSH public key. It should start with ssh-ed25519, ssh-rsa or ecdsa-sha2-….");
  }
  const blob = BASE64.test(b64) ? Buffer.from(b64, "base64") : undefined;
  if (!blob || !blobFits(blob, type)) throw new KeyError(`That ${type} key is damaged. Copy it again, all on one line.`);
  const comment = rest.join(" ");
  if (comment && !/^[\x20-\x7e]{1,200}$/.test(comment)) throw new KeyError("The key's comment has characters Vela can't store. Remove the comment and try again.");
  return { key: comment ? `${type} ${b64} ${comment}` : `${type} ${b64}`, type, fingerprint: fingerprint(blob), ...(comment ? { comment } : {}) };
}

/** Parses several lines, dropping blank ones and repeats of the same key. */
export function parseKeys(lines: string[]): SetupKey[] {
  const seen = new Set<string>();
  const keys: SetupKey[] = [];
  for (const l of lines) {
    if (!l.trim()) continue;
    const k = parseKeyLine(l);
    if (seen.has(k.fingerprint)) continue;
    seen.add(k.fingerprint);
    keys.push(k);
  }
  if (keys.length > MAX_KEYS) throw new KeyError(`That's ${keys.length} keys; Vela takes at most ${MAX_KEYS}.`);
  return keys;
}

/** Fetches https://github.com/<user>.keys and nothing else (no redirects). Keys get the comment `<user>@github`. */
export async function fetchGithubKeys(user: string, f: typeof fetch = fetch): Promise<SetupKey[]> {
  if (!GITHUB_USER.test(user)) throw new KeyError("That isn't a GitHub username. It can have letters, digits and dashes, up to 39 characters.");
  let res: Response;
  try {
    res = await f(`https://github.com/${user}.keys`, { redirect: "error", signal: AbortSignal.timeout(10_000) });
  } catch {
    throw new KeyError("Couldn't reach GitHub. Check the box's internet connection, or paste a key instead.");
  }
  if (res.status === 404) throw new KeyError(`GitHub has no user named ${user}. Check the spelling.`);
  if (!res.ok) throw new KeyError(`GitHub answered with an error (${res.status}). Try again, or paste a key instead.`);
  const body = await res.text();
  if (body.length > MAX_BODY) throw new KeyError("GitHub sent more keys than Vela can take. Paste the keys you want instead.");
  const lines = body.split("\n").filter((l) => l.trim()).map((l) => `${l.trim()} ${user}@github`);
  if (!lines.length) throw new KeyError(`${user} has no SSH keys on GitHub. Add one at github.com/settings/keys, or paste a key instead.`);
  return parseKeys(lines);
}
