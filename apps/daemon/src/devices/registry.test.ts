import { describe, expect, it, vi } from "vitest";
import type { DeviceConfig } from "../config.js";
import { DeviceRegistry, NoTransmitter, NotFound, NO_TRANSMITTER_MESSAGE } from "./registry.js";
import { TvUnreachable, type TvController } from "./homeAssistant.js";

const irdbRoot = new URL("../../test/fixtures", import.meta.url).pathname;

const tvCfg: DeviceConfig = { id: "tv", name: "TV", kind: "tv", entity: "media_player.tv", powerOnFallback: { file: "samsung.ir", button: "Power" } };
const irCfg: DeviceConfig = { id: "amp", name: "Amp", kind: "ir", transmitter: "irctl", codes: { vol_up: { file: "samsung.ir", button: "Vol_up" }, bad: { file: "samsung.ir", button: "Rc5_test" } } };

function setup(tv: Partial<TvController> = {}, cfg: DeviceConfig[] = [tvCfg, irCfg]) {
  const controller: TvController = { command: vi.fn(async () => {}), state: vi.fn(async () => "on"), ...tv };
  const irctl = { send: vi.fn(async () => {}) };
  const audiojack = { send: vi.fn(async () => {}) };
  const sleep = vi.fn(async () => {});
  const reg = new DeviceRegistry(cfg, { ha: () => controller, irdbRoot, transmitters: { irctl, audiojack }, sleep });
  return { reg, controller, irctl, audiojack, sleep };
}

describe("DeviceRegistry", () => {
  it("lists devices with commands", () => {
    const { reg } = setup();
    expect(reg.list()).toEqual([
      { id: "tv", name: "TV", kind: "tv", commands: ["power_on", "power_off", "volume_up", "volume_down", "mute"] },
      { id: "amp", name: "Amp", kind: "ir", commands: ["vol_up", "bad"] },
    ]);
  });

  it("power_on falls back to IR when HA turn_on fails", async () => {
    const { reg, irctl, sleep } = setup({ command: vi.fn(async () => { throw new TvUnreachable(); }) });
    await reg.run("tv", "power_on");
    expect(irctl.send).toHaveBeenCalledTimes(1);
    expect(irctl.send.mock.calls[0]![0]).toMatchObject({ carrierHz: 38000 });
    expect(sleep).not.toHaveBeenCalled();
  });

  it("power_on falls back to IR after 8 s still off", async () => {
    const { reg, irctl, sleep, controller } = setup({ state: vi.fn(async () => "off") });
    await reg.run("tv", "power_on");
    expect(controller.command).toHaveBeenCalledWith("power_on");
    expect(sleep).toHaveBeenCalledTimes(8);
    expect(sleep).toHaveBeenCalledWith(1000);
    expect(irctl.send).toHaveBeenCalledTimes(1);
  });

  it("power_on returns as soon as the TV reports on", async () => {
    let t = 0;
    const sleep = vi.fn(async (ms: number) => void (t += ms));
    const state = vi.fn(async () => (t >= 2000 ? "on" : "off"));
    const controller: TvController = { command: vi.fn(async () => {}), state };
    const irctl = { send: vi.fn(async () => {}) };
    const reg = new DeviceRegistry([tvCfg], { ha: () => controller, irdbRoot, transmitters: { irctl }, sleep });
    await reg.run("tv", "power_on");
    expect(irctl.send).not.toHaveBeenCalled();
    expect(state.mock.calls.length).toBeLessThanOrEqual(3);
  });

  it("two concurrent IR sends run one after the other", async () => {
    const log: string[] = [];
    let release: () => void = () => {};
    const gate = new Promise<void>((r) => (release = r));
    const irctl = {
      send: vi.fn(async () => {
        log.push("start");
        if (log.length === 1) await gate;
        log.push("end");
      }),
    };
    const reg = new DeviceRegistry([irCfg], { irdbRoot, transmitters: { irctl } });
    const a = reg.run("amp", "vol_up");
    const b = reg.run("amp", "vol_up");
    await new Promise((r) => setTimeout(r, 20));
    expect(log).toEqual(["start"]);
    release();
    await Promise.all([a, b]);
    expect(log).toEqual(["start", "end", "start", "end"]);
  });

  it("power_on does not use IR when the TV turns on", async () => {
    const { reg, irctl } = setup();
    await reg.run("tv", "power_on");
    expect(irctl.send).not.toHaveBeenCalled();
  });

  it("power_on rethrows when HA fails and there is no fallback", async () => {
    const { reg } = setup({ command: vi.fn(async () => { throw new TvUnreachable(); }) }, [{ ...tvCfg, powerOnFallback: undefined }]);
    await expect(reg.run("tv", "power_on")).rejects.toBeInstanceOf(TvUnreachable);
  });

  it("accepts hyphenated command names from vela-ctl", async () => {
    const { reg, controller } = setup();
    await reg.run("tv", "volume-up");
    expect(controller.command).toHaveBeenCalledWith("volume_up");
  });

  it("sends an IR code through the configured transmitter", async () => {
    const { reg, irctl, audiojack } = setup();
    await reg.run("amp", "vol_up");
    expect(irctl.send).toHaveBeenCalledTimes(1);
    expect(audiojack.send).not.toHaveBeenCalled();
  });

  it("rejects with the plug-in message when the transmitter is missing", async () => {
    const reg = new DeviceRegistry([irCfg], { irdbRoot, transmitters: {} });
    const e = await reg.run("amp", "vol_up").catch((x: unknown) => x);
    expect(e).toBeInstanceOf(NoTransmitter);
    expect((e as Error).message).toBe(NO_TRANSMITTER_MESSAGE);
    expect(NO_TRANSMITTER_MESSAGE).toBe("No IR transmitter found. Plug in the IR dongle or blaster and try again.");
  });

  it("rejects IR files outside the database root", async () => {
    const cfg: DeviceConfig = { ...irCfg, kind: "ir", codes: { x: { file: "../../../etc/passwd", button: "x" } } } as DeviceConfig;
    const reg = new DeviceRegistry([cfg], { irdbRoot, transmitters: { irctl: { send: async () => {} } } });
    await expect(reg.run("amp", "x")).rejects.toThrow(/outside/);
  });

  it("unknown command → NotFound", async () => {
    const { reg } = setup();
    await expect(reg.run("tv", "explode")).rejects.toBeInstanceOf(NotFound);
    await expect(reg.run("nope", "mute")).rejects.toBeInstanceOf(NotFound);
    await expect(reg.run("amp", "explode")).rejects.toBeInstanceOf(NotFound);
  });
});
