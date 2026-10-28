import { execFileSync } from "node:child_process";
import { loadConfig, loadSecrets } from "./config.js";
import { JellyfinClient, loginAndSave, toCard } from "./media/jellyfin.js";
import { buildHomeState, startHomePolling } from "./media/homeState.js";
import { createMockProvider } from "./media/mock.js";
import { loadManifests } from "./manifests.js";
import { buildServer, EventBus, PORT } from "./server.js";
import { AppRunner, defaultSpawn } from "./session/appRunner.js";
import { flatpakStatus } from "./session/flatpak.js";
import { createNoopSway, createSway } from "./session/sway.js";

const config = loadConfig(process.env.VELA_CONFIG ?? "/etc/vela/vela.toml");
const manifests = loadManifests(config.manifestDirs);
const bus = new EventBus();

function haveSwaymsg(): boolean {
  try {
    execFileSync("sh", ["-c", "command -v swaymsg"], { stdio: "ignore" });
    return true;
  } catch {
    return false;
  }
}
const sway = process.env.VELA_SWAY === "noop" || !haveSwaymsg() ? createNoopSway() : createSway();

const status = async (m: (typeof manifests)[number]) => (m.kind === "flatpak" ? flatpakStatus(m.flatpak!) : "ready" as const);
const runner = new AppRunner({ sway, manifests, spawn: defaultSpawn, status, emit: bus.emit });

const SECRETS_PATH = process.env.VELA_SECRETS ?? "/var/lib/vela/secrets.json";
let jellyfin: JellyfinClient | undefined;
if (config.media.provider === "jellyfin" && config.media.jellyfin) {
  let secrets = loadSecrets(SECRETS_PATH);
  jellyfin = new JellyfinClient({ url: config.media.jellyfin.url, token: secrets.jellyfinToken, userId: secrets.jellyfinUserId });
  const pw = process.env.VELA_JELLYFIN_PASSWORD;
  if (!secrets.jellyfinToken && pw) {
    // First run: exchange the password for a token once and keep only the token.
    loginAndSave(jellyfin, config.media.jellyfin.username, pw, SECRETS_PATH, secrets).then(
      (s) => void (secrets = s),
      (e) => console.error("vela: Jellyfin login failed:", (e as Error).message),
    );
  }
}
const jf = jellyfin;
const mock = createMockProvider();
const home = () =>
  buildHomeState({
    manifests,
    status,
    media: jf ? { resume: async () => (await jf.resume()).map((i) => toCard(i, mock.hero("sintel").backdropUrl)) } : null,
    mock,
  });
startHomePolling(home, bus.emit);
const app = buildServer({ config, manifests, runner, bus, home, jellyfin });

app.listen({ host: config.server.listen, port: PORT }).then(
  () => console.log(`vela daemon listening on ${config.server.listen}:${PORT}`),
  (e) => {
    console.error(e);
    process.exit(1);
  },
);
