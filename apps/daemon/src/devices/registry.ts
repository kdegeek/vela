import fs from "node:fs";
import path from "node:path";
import type { Device } from "@vela/shared";
import type { DeviceConfig, IrRef } from "../config.js";
import { TV_COMMANDS, TvUnreachable, type TvCommand, type TvController } from "./homeAssistant.js";
import { encode } from "./ir/encode.js";
import { parseFlipperIr } from "./ir/flipper.js";
import type { IrTransmitter } from "./ir/irctl.js";

export const NO_TRANSMITTER_MESSAGE = "No IR transmitter found. Plug in the IR dongle or blaster and try again.";

export class NotFound extends Error {
  constructor(what: string) {
    super(`Unknown ${what}.`);
    this.name = "NotFound";
  }
}

export class NoTransmitter extends Error {
  constructor(message = NO_TRANSMITTER_MESSAGE) {
    super(message);
    this.name = "NoTransmitter";
  }
}

export interface RegistryDeps {
  /** Builds a controller for a Home Assistant entity; absent when HA isn't configured. */
  ha?: (entity: string) => TvController;
  irdbRoot: string;
  /** Only the transmitters that are actually present. */
  transmitters: Partial<Record<"irctl" | "audiojack", IrTransmitter>>;
  sleep?: (ms: number) => Promise<void>;
  /** config.volumeDevice: listed first, so the volume keys use it. */
  volumeDevice?: string;
}

const POWER_ON_SETTLE_MS = 8000;
const POWER_ON_POLL_MS = 1000;
const norm = (s: string) => s.replace(/-/g, "_");

export class DeviceRegistry {
  private sleep: (ms: number) => Promise<void>;
  constructor(private cfg: DeviceConfig[], private deps: RegistryDeps) {
    this.sleep = deps.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms)));
  }

  /** The volume device (config.volumeDevice, else the first TV) comes first: vela-ctl volume uses the first device with the command. */
  list(): Device[] {
    const want = this.deps.volumeDevice;
    const vol = (want && this.cfg.find((d) => d.id === want)) || this.cfg.find((d) => d.kind === "tv");
    const ordered = vol ? [vol, ...this.cfg.filter((d) => d !== vol)] : this.cfg;
    return ordered.map((d) => ({
      id: d.id,
      name: d.name,
      kind: d.kind,
      commands: d.kind === "tv" ? [...TV_COMMANDS] : Object.keys(d.codes),
    }));
  }

  async run(id: string, command: string): Promise<void> {
    const d = this.cfg.find((x) => x.id === id);
    if (!d) throw new NotFound("device");
    if (d.kind === "tv") {
      const cmd = norm(command);
      if (!(TV_COMMANDS as string[]).includes(cmd)) throw new NotFound("command");
      return this.runTv(d, cmd as TvCommand);
    }
    const key = Object.keys(d.codes).find((k) => k === command) ?? Object.keys(d.codes).find((k) => norm(k) === norm(command));
    if (!key) throw new NotFound("command");
    return this.sendIr(d.codes[key]!, d.transmitter);
  }

  private async runTv(d: Extract<DeviceConfig, { kind: "tv" }>, cmd: TvCommand): Promise<void> {
    const tv = this.deps.ha?.(d.entity);
    const fallback = cmd === "power_on" ? d.powerOnFallback : undefined;
    if (!tv) {
      if (fallback) return this.sendIr(fallback);
      throw new TvUnreachable("Home Assistant isn't configured");
    }
    try {
      await tv.command(cmd);
    } catch (e) {
      if (fallback) return this.sendIr(fallback);
      throw e;
    }
    if (!fallback) return;
    // Some TVs accept turn_on over the network but stay in standby. Poll until it reports on; at the cap, use the IR code.
    for (let waited = 0; ; ) {
      await this.sleep(POWER_ON_POLL_MS);
      waited += POWER_ON_POLL_MS;
      let state: string;
      try {
        state = await tv.state();
      } catch {
        return; // can't tell; don't fire IR blindly
      }
      if (state !== "off" && state !== "unavailable") return;
      if (waited >= POWER_ON_SETTLE_MS) return this.sendIr(fallback);
    }
  }

  private chain: Promise<unknown> = Promise.resolve();
  /** Runs transmissions one at a time so two commands never overlap on the emitter. */
  private serialized<T>(fn: () => Promise<T>): Promise<T> {
    const r = this.chain.then(fn, fn);
    this.chain = r.catch(() => {});
    return r;
  }

  private async sendIr(ref: IrRef, preferred?: "irctl" | "audiojack"): Promise<void> {
    const t = preferred ? this.deps.transmitters[preferred] : (this.deps.transmitters.irctl ?? this.deps.transmitters.audiojack);
    if (!t) throw new NoTransmitter();
    const root = path.resolve(this.deps.irdbRoot);
    const file = path.resolve(root, ref.file);
    if (file !== root && !file.startsWith(root + path.sep)) throw new Error(`IR file ${ref.file} is outside the IR database.`);
    let text: string;
    try {
      text = fs.readFileSync(file, "utf8");
    } catch {
      throw new Error(`IR file ${ref.file} not found in the IR database.`);
    }
    const sig = parseFlipperIr(text).find((s) => s.name === ref.button);
    if (!sig) throw new Error(`Button ${ref.button} not found in ${ref.file}.`);
    const timings = encode(sig);
    await this.serialized(() => t.send(timings));
  }
}
