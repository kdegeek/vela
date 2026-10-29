import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { loadSettings } from "./config.js";
import { JellyfinClient, toCard } from "./media/jellyfin.js";
import { buildHomeState, startHomePolling } from "./media/homeState.js";
import { loadGlyphs } from "./media/glyphs.js";
import { createMockProvider } from "./media/mock.js";
import { guideHandler, watchGamepads } from "./input/evdev.js";
import { loadManifests } from "./manifests.js";
import { assetsDir, buildServer, EventBus, PORT } from "./server.js";
import { AppRunner, defaultSpawn } from "./session/appRunner.js";
import { flatpakStatus } from "./session/flatpak.js";
import { createPlayer } from "./playback/jellyfinReporter.js";
import { startMpv } from "./playback/mpv.js";
import { haInsideTemp, haTv } from "./devices/homeAssistant.js";
import { audioJackTransmitter, jackSinkFinder } from "./devices/ir/audioJack.js";
import { irCtlTransmitter, type IrTransmitter } from "./devices/ir/irctl.js";
import { DeviceRegistry } from "./devices/registry.js";
import { createNoopSway, createSway } from "./session/sway.js";

const SECRETS_PATH = process.env.VELA_SECRETS ?? "/var/lib/vela/secrets.json";
// Never throws: a broken file gives defaults and a status line saying which file to fix.
const settings = loadSettings(process.env.VELA_CONFIG ?? "/etc/vela/vela.toml", SECRETS_PATH);
const { config, secrets, problem } = settings;
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

let jellyfin: JellyfinClient | undefined;
if (config.media.provider === "jellyfin" && config.media.jellyfin) {
  jellyfin = new JellyfinClient({ url: config.media.jellyfin.url, token: secrets.jellyfinToken, userId: secrets.jellyfinUserId });
}
const jf = jellyfin;

const haToken = secrets.homeAssistantToken;
const haOpts = config.homeAssistant && haToken ? { url: config.homeAssistant.url, token: haToken } : undefined;
const insideTempEntity = config.homeAssistant?.insideTempEntity;
const insideTemp = haOpts && insideTempEntity ? () => haInsideTemp(insideTempEntity, haOpts) : undefined;

/** First /dev/lirc* device, unless VELA_IRCTL_DEVICE names one. */
function lircDevice(): string | undefined {
  if (process.env.VELA_IRCTL_DEVICE) return process.env.VELA_IRCTL_DEVICE;
  try {
    const n = fs.readdirSync("/dev").filter((f) => /^lirc\d+$/.test(f)).sort((a, b) => Number(a.slice(4)) - Number(b.slice(4)));
    return n[0] ? `/dev/${n[0]}` : undefined;
  } catch {
    return undefined;
  }
}
const transmitters: Partial<Record<"irctl" | "audiojack", IrTransmitter>> = {};
// The jack blaster plays on the headphone-jack sink (ir.audioJackSink, or found in pw-dump at send time), never the
// default HDMI sink. VELA_AUDIOJACK_DEVICE names an ALSA device instead (aplay).
const jackDevice = process.env.VELA_AUDIOJACK_DEVICE;
transmitters.audiojack = audioJackTransmitter(jackDevice ? { alsaDevice: jackDevice } : { sink: jackSinkFinder(config.ir.audioJackSink) });
const lirc = lircDevice();
if (lirc) transmitters.irctl = irCtlTransmitter(lirc);
const devices = new DeviceRegistry(config.devices, {
  ha: haOpts ? (entity) => haTv(entity, haOpts) : undefined,
  irdbRoot: process.env.VELA_IRDB ?? "/usr/share/vela/irdb",
  transmitters,
});

const mock = createMockProvider();
const glyphs = loadGlyphs(manifests, path.join(assetsDir(), "glyphs"));
const home = () =>
  buildHomeState({
    manifests,
    status,
    media: jf ? { resume: async () => (await jf.resume()).map((i) => toCard(i, mock.hero("sintel").backdropUrl)) } : null,
    mock,
    insideTemp,
    clock24h: config.ui.clock24h,
    effects: config.ui.effects,
    glyphs,
    problem,
  });
startHomePolling(home, bus.emit);
const play = jf ? createPlayer({ jf, start: startMpv, goHome: () => runner.goHome(), emit: bus.emit, runner }) : undefined;
const app = buildServer({ config, manifests, runner, bus, home, jellyfin, play, devices });

app.listen({ host: config.server.listen, port: PORT }).then(
  () => console.log(`vela daemon listening on ${config.server.listen}:${PORT}`),
  (e) => {
    console.error(e);
    process.exit(1);
  },
);

let stopGamepads: () => void = () => {};
if (process.platform === "linux" && process.env.VELA_GAMEPAD !== "0") {
  const guide = guideHandler({
    goHome: () => void runner.goHome().catch((e) => console.error("vela: goHome", (e as Error).message)),
    passthrough: () => manifests.find((m) => m.id === runner.current())?.homePassthrough === true,
  });
  stopGamepads = watchGamepads(guide, { onDrop: guide.reset });
}

let shuttingDown = false;
function shutdown(): void {
  if (shuttingDown) return;
  shuttingDown = true;
  stopGamepads();
  app.close().then(
    () => process.exit(0),
    (e) => {
      console.error("vela: shutdown", (e as Error).message);
      process.exit(1);
    },
  );
}
process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);
