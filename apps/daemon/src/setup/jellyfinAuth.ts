import { loadSecrets, saveSecrets, type Secrets } from "../config.js";
import { JellyfinAuthError, JellyfinClient } from "../media/jellyfin.js";
import { SetupError } from "./state.js";

/** "192.168.1.5:8096" → "http://192.168.1.5:8096"; only http(s), no credentials, no trailing slash. */
export function normalizeJellyfinUrl(input: string): string {
  const text = input.trim();
  let u: URL;
  try {
    u = new URL(/^[a-z][a-z0-9+.-]*:\/\//i.test(text) ? text : `http://${text}`);
  } catch {
    throw new SetupError(400, "That isn't a web address. It looks like http://192.168.1.5:8096.");
  }
  if ((u.protocol !== "http:" && u.protocol !== "https:") || !u.hostname || u.username || u.password) {
    throw new SetupError(400, "Use the server's http:// or https:// address, like http://192.168.1.5:8096.");
  }
  return `${u.origin}${u.pathname}`.replace(/\/+$/, "");
}

/**
 * Signs in with AuthenticateByName and keeps only the returned access token and user id (merged into secrets.json
 * through the 0600 writer). The password is used for that one request and never stored, logged or put in an error.
 */
export async function signInJellyfin(opts: {
  url: string;
  username: string;
  password: string;
  secretsPath: string;
  fetch?: typeof fetch;
  deviceId?: string;
}): Promise<{ url: string; server: string }> {
  const url = normalizeJellyfinUrl(opts.url);
  const f = opts.fetch ?? fetch;
  let server: string;
  try {
    const res = await f(`${url}/System/Info/Public`, { signal: AbortSignal.timeout(10_000) });
    if (!res.ok) throw new Error(String(res.status));
    const info = (await res.json()) as { ServerName?: unknown; Id?: unknown };
    if (typeof info.Id !== "string") throw new Error("not jellyfin");
    server = typeof info.ServerName === "string" && info.ServerName.trim() ? info.ServerName.trim().slice(0, 100) : new URL(url).host;
  } catch {
    throw new SetupError(502, `Couldn't reach a Jellyfin server at ${url}. Check the address and that the server is on.`);
  }

  const client = new JellyfinClient({ url, fetch: f, deviceId: opts.deviceId });
  let auth: { token: string; userId: string };
  try {
    auth = await client.authenticate(opts.username, opts.password);
  } catch (e) {
    if (e instanceof JellyfinAuthError) throw new SetupError(422, "Jellyfin didn't accept that username and password. Check them and try again.");
    throw new SetupError(502, `${server} didn't finish the sign-in. Try again in a moment.`);
  }
  if (typeof auth.token !== "string" || !auth.token || typeof auth.userId !== "string" || !auth.userId) {
    throw new SetupError(502, `${server} answered the sign-in without a token. Check that it is a Jellyfin server.`);
  }

  let secrets: Secrets = {};
  try {
    secrets = loadSecrets(opts.secretsPath);
  } catch (e) {
    console.error("vela: setup replaces an unreadable secrets file:", (e as Error).message);
  }
  try {
    saveSecrets(opts.secretsPath, { ...secrets, jellyfinToken: auth.token, jellyfinUserId: auth.userId });
  } catch (e) {
    console.error("vela: setup could not write secrets:", (e as NodeJS.ErrnoException).code ?? (e as Error).message);
    throw new SetupError(500, "Vela couldn't save the sign-in on the box. Restart it and try again.");
  }
  return { url, server };
}
