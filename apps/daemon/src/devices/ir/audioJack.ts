import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import type { Timings } from "./encode.js";
import type { IrTransmitter } from "./irctl.js";
import { defaultRun, type Run } from "./run.js";

const SAMPLE_RATE = 48000;
const AMPLITUDE = 0.9;

/**
 * Interleaved stereo. During pulses L = sin(pi*f*t)*0.9 and R = -L: the two channels drive antiparallel LEDs, so each LED
 * lights once per period of f/2 and together they flash at f. Spaces are silence.
 */
export function renderAudio(t: Timings, sampleRate = SAMPLE_RATE): Int16Array {
  // Round cumulative boundaries (not each duration) so rounding error does not accumulate.
  const bounds: number[] = [];
  let acc = 0;
  for (const d of t.durationsUs) bounds.push(Math.round(((acc += d) * sampleRate) / 1e6));
  const out = new Int16Array((bounds.at(-1) ?? 0) * 2);
  let start = 0;
  bounds.forEach((end, i) => {
    if (i % 2 === 0) {
      for (let n = 0; n < end - start; n++) {
        const l = Math.max(-32768, Math.min(32767, Math.round(Math.sin((Math.PI * t.carrierHz * n) / sampleRate) * AMPLITUDE * 32767)));
        out[(start + n) * 2] = l;
        out[(start + n) * 2 + 1] = -l;
      }
    }
    start = end;
  });
  return out;
}

/** Wraps interleaved stereo s16le samples in a canonical 44-byte-header WAV. */
export function wavFromSamples(samples: Int16Array, sampleRate = SAMPLE_RATE): Buffer {
  const dataBytes = samples.length * 2;
  const b = Buffer.alloc(44 + dataBytes);
  b.write("RIFF", 0, "ascii");
  b.writeUInt32LE(36 + dataBytes, 4);
  b.write("WAVEfmt ", 8, "ascii");
  b.writeUInt32LE(16, 16);
  b.writeUInt16LE(1, 20); // PCM
  b.writeUInt16LE(2, 22); // channels
  b.writeUInt32LE(sampleRate, 24);
  b.writeUInt32LE(sampleRate * 4, 28);
  b.writeUInt16LE(4, 32);
  b.writeUInt16LE(16, 34);
  b.write("data", 36, "ascii");
  b.writeUInt32LE(dataBytes, 40);
  samples.forEach((v, i) => b.writeInt16LE(v, 44 + i * 2));
  return b;
}

/** Plays the rendered burst through a headphone-jack IR blaster. The default device goes through PipeWire, falling back to ALSA. */
export function audioJackTransmitter(alsaDevice: string, run: Run = defaultRun): IrTransmitter {
  return {
    async send(t) {
      const dir = fs.mkdtempSync(path.join(os.tmpdir(), "vela-ir-"));
      const file = path.join(dir, "send.wav");
      try {
        fs.writeFileSync(file, wavFromSamples(renderAudio(t)));
        if (alsaDevice === "default") {
          try {
            await run("pw-play", [file]);
            return;
          } catch {
            // PipeWire isn't available; fall through to ALSA.
          }
        }
        await run("aplay", ["-D", alsaDevice, file]);
      } finally {
        fs.rmSync(dir, { recursive: true, force: true });
      }
    },
  };
}
