import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import type { Timings } from "./encode.js";
import type { IrTransmitter } from "./irctl.js";
import { NoTransmitter } from "../registry.js";
import { defaultCapture, defaultRun, type Capture, type Run } from "./run.js";

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

export const JACK_MISSING_MESSAGE =
  "No headphone-jack output found for the IR blaster. Plug it in, or set ir.audioJackSink in /etc/vela/vela.toml (names are in wpctl status).";

interface PwObject {
  type?: string;
  info?: { props?: Record<string, unknown> } | null;
}

/**
 * The PipeWire sink node for the headphone jack, from `pw-dump` output: an Audio/Sink whose name, description or nick
 * says headphone (preferred) or analog. HDMI is never picked; the IR burst must not go to the TV's speakers.
 */
export function selectJackSink(dump: unknown): string | undefined {
  if (!Array.isArray(dump)) return undefined;
  const sinks = (dump as PwObject[])
    .filter((o) => o?.type === "PipeWire:Interface:Node" && o.info?.props?.["media.class"] === "Audio/Sink")
    .map((o) => o.info!.props!)
    .filter((p) => typeof p["node.name"] === "string");
  const text = (p: Record<string, unknown>) => ["node.name", "node.description", "node.nick"].map((k) => String(p[k] ?? "")).join(" ");
  const ok = sinks.filter((p) => !/hdmi|displayport/i.test(text(p)));
  const pick = ok.find((p) => /headphone/i.test(text(p))) ?? ok.find((p) => /analog/i.test(text(p)));
  return pick?.["node.name"] as string | undefined;
}

/** The configured sink, else the one found in pw-dump (cached once found; PipeWire may start after the daemon). */
export function jackSinkFinder(configured: string | undefined, capture: Capture = defaultCapture): () => Promise<string | undefined> {
  let found = configured;
  return async () => {
    if (found) return found;
    try {
      found = selectJackSink(JSON.parse(await capture("pw-dump", [])));
    } catch (e) {
      console.error("vela: pw-dump:", (e as Error).message);
    }
    return found;
  };
}

/**
 * Plays the rendered burst through a headphone-jack IR blaster: `pw-play --target <jack sink>` at full stream volume, or
 * `aplay -D <device>` for an ALSA device override (VELA_AUDIOJACK_DEVICE). Without a jack sink it throws NoTransmitter.
 */
export function audioJackTransmitter(target: { sink: () => Promise<string | undefined> } | { alsaDevice: string }, run: Run = defaultRun): IrTransmitter {
  return {
    async send(t) {
      const sink = "sink" in target ? await target.sink() : undefined;
      if ("sink" in target && !sink) throw new NoTransmitter(JACK_MISSING_MESSAGE);
      const dir = fs.mkdtempSync(path.join(os.tmpdir(), "vela-ir-"));
      const file = path.join(dir, "send.wav");
      try {
        fs.writeFileSync(file, wavFromSamples(renderAudio(t)));
        if (sink) await run("pw-play", ["--target", sink, "--volume", "1.0", file]);
        else await run("aplay", ["-D", (target as { alsaDevice: string }).alsaDevice, file]);
      } finally {
        fs.rmSync(dir, { recursive: true, force: true });
      }
    },
  };
}
