import { execFileSync } from "node:child_process";
import { loadConfig } from "./config.js";
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

const runner = new AppRunner({
  sway,
  manifests,
  spawn: defaultSpawn,
  status: async (m) => (m.kind === "flatpak" ? flatpakStatus(m.flatpak!) : "ready"),
  emit: bus.emit,
});
const app = buildServer({ config, manifests, runner, bus });

app.listen({ host: config.server.listen, port: PORT }).then(
  () => console.log(`vela daemon listening on ${config.server.listen}:${PORT}`),
  (e) => {
    console.error(e);
    process.exit(1);
  },
);
