import fs from "node:fs";
import { describe, expect, it, vi } from "vitest";
import { audioJackTransmitter, JACK_MISSING_MESSAGE, jackSinkFinder, renderAudio, selectJackSink, wavFromSamples } from "./audioJack.js";
import { NoTransmitter } from "../registry.js";

const dump = JSON.parse(fs.readFileSync(new URL("../../../test/fixtures/pw-dump.json", import.meta.url), "utf8")) as unknown[];

describe("renderAudio", () => {
  it("1 ms pulse at 38 kHz renders 48 frames with L = −R", () => {
    const a = renderAudio({ carrierHz: 38000, durationsUs: [1000] });
    expect(a).toHaveLength(96);
    let nonzero = 0;
    for (let i = 0; i < 48; i++) {
      expect(a[2 * i + 1]! + a[2 * i]!).toBe(0);
      if (a[2 * i] !== 0) nonzero++;
    }
    expect(nonzero).toBeGreaterThan(0);
    expect(Math.max(...a)).toBeLessThanOrEqual(Math.round(0.9 * 32767));
  });

  it("space renders zeros", () => {
    const a = renderAudio({ carrierHz: 38000, durationsUs: [1000, 1000] });
    expect(a).toHaveLength(192);
    expect(Array.from(a.slice(96)).every((v) => v === 0)).toBe(true);
  });
});

describe("wavFromSamples", () => {
  it("writes a correct 48 kHz stereo s16le RIFF header", () => {
    const w = wavFromSamples(new Int16Array(96), 48000);
    expect(w.toString("ascii", 0, 4)).toBe("RIFF");
    expect(w.readUInt32LE(4)).toBe(w.length - 8);
    expect(w.toString("ascii", 8, 16)).toBe("WAVEfmt ");
    expect(w.readUInt32LE(16)).toBe(16);
    expect(w.readUInt16LE(20)).toBe(1);
    expect(w.readUInt16LE(22)).toBe(2);
    expect(w.readUInt32LE(24)).toBe(48000);
    expect(w.readUInt32LE(28)).toBe(48000 * 4);
    expect(w.readUInt16LE(32)).toBe(4);
    expect(w.readUInt16LE(34)).toBe(16);
    expect(w.toString("ascii", 36, 40)).toBe("data");
    expect(w.readUInt32LE(40)).toBe(192);
    expect(w.length).toBe(44 + 192);
  });
});

describe("selectJackSink", () => {
  it("picks the analog/headphone output node from pw-dump, not HDMI or an input", () => {
    expect(selectJackSink(dump)).toBe("alsa_output.pci-0000_00_1f.3.analog-stereo");
  });

  it("matches a T2-style Headphones sink by description", () => {
    const t2 = [{ type: "PipeWire:Interface:Node", info: { props: { "node.name": "alsa_output.platform-apple_t2.HiFi__hw_aaudio__sink", "node.description": "Headphones", "media.class": "Audio/Sink" } } }];
    expect(selectJackSink(t2)).toBe("alsa_output.platform-apple_t2.HiFi__hw_aaudio__sink");
  });

  it("prefers a headphone node over a generic analog one", () => {
    const both = [
      { type: "PipeWire:Interface:Node", info: { props: { "node.name": "a.analog-surround-51", "media.class": "Audio/Sink" } } },
      { type: "PipeWire:Interface:Node", info: { props: { "node.name": "b.headphones", "media.class": "Audio/Sink" } } },
    ];
    expect(selectJackSink(both)).toBe("b.headphones");
  });

  it("is undefined with only HDMI, or for junk input", () => {
    expect(selectJackSink(dump.filter((o) => (o as { id: number }).id !== 58))).toBeUndefined();
    expect(selectJackSink({})).toBeUndefined();
    expect(selectJackSink(null)).toBeUndefined();
  });
});

describe("jackSinkFinder", () => {
  it("a configured sink wins without running pw-dump", async () => {
    const capture = vi.fn(async () => "[]");
    expect(await jackSinkFinder("my.sink", capture)()).toBe("my.sink");
    expect(capture).not.toHaveBeenCalled();
  });

  it("reads pw-dump once a sink is found, and retries while none is", async () => {
    let out = "[]";
    const capture = vi.fn(async () => out);
    const find = jackSinkFinder(undefined, capture);
    expect(await find()).toBeUndefined();
    out = JSON.stringify(dump);
    expect(await find()).toBe("alsa_output.pci-0000_00_1f.3.analog-stereo");
    expect(await find()).toBe("alsa_output.pci-0000_00_1f.3.analog-stereo");
    expect(capture).toHaveBeenCalledTimes(2);
    expect(capture).toHaveBeenCalledWith("pw-dump", []);
  });

  it("pw-dump failing reads as no sink", async () => {
    const find = jackSinkFinder(undefined, async () => {
      throw new Error("pw-dump failed");
    });
    expect(await find()).toBeUndefined();
  });
});

describe("audioJackTransmitter", () => {
  const burst = { carrierHz: 38000, durationsUs: [1000] };

  it("plays on the headphone-jack sink with pw-play --target at full volume", async () => {
    const run = vi.fn(async () => {});
    await audioJackTransmitter({ sink: async () => "alsa_output.analog-stereo" }, run).send(burst);
    expect(run).toHaveBeenCalledTimes(1);
    expect(run.mock.calls[0]).toEqual(["pw-play", ["--target", "alsa_output.analog-stereo", "--volume", "1.0", expect.stringMatching(/\.wav$/)]]);
  });

  it("never falls back to the default (HDMI) sink: no jack sink is NoTransmitter (503)", async () => {
    const run = vi.fn(async () => {});
    const err = await audioJackTransmitter({ sink: async () => undefined }, run).send(burst).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(NoTransmitter);
    expect((err as Error).message).toBe(JACK_MISSING_MESSAGE);
    expect(run).not.toHaveBeenCalled();
  });

  it("VELA_AUDIOJACK_DEVICE-style ALSA override uses aplay -D", async () => {
    const run = vi.fn(async () => {});
    await audioJackTransmitter({ alsaDevice: "hw:1,0" }, run).send(burst);
    expect(run.mock.calls[0]).toEqual(["aplay", ["-D", "hw:1,0", expect.stringMatching(/\.wav$/)]]);
  });
});
