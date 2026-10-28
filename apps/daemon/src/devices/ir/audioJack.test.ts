import { describe, expect, it, vi } from "vitest";
import { audioJackTransmitter, renderAudio, wavFromSamples } from "./audioJack.js";

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

describe("audioJackTransmitter", () => {
  it("plays with pw-play on the default device", async () => {
    const run = vi.fn(async () => {});
    await audioJackTransmitter("default", run).send({ carrierHz: 38000, durationsUs: [1000] });
    expect(run.mock.calls[0]![0]).toBe("pw-play");
  });

  it("falls back to aplay -D when pw-play fails", async () => {
    const run = vi.fn(async (cmd: string) => {
      if (cmd === "pw-play") throw new Error("ENOENT");
    });
    await audioJackTransmitter("default", run).send({ carrierHz: 38000, durationsUs: [1000] });
    expect(run.mock.calls[1]!.slice(0, 2)).toEqual(["aplay", ["-D", "default", expect.stringMatching(/\.wav$/)]]);
  });

  it("uses aplay directly for a named ALSA device", async () => {
    const run = vi.fn(async () => {});
    await audioJackTransmitter("hw:1,0", run).send({ carrierHz: 38000, durationsUs: [1000] });
    expect(run).toHaveBeenCalledTimes(1);
    expect(run.mock.calls[0]![1]).toContain("hw:1,0");
  });
});
