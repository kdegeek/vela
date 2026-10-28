import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import type { Timings } from "./encode.js";
import { defaultRun, type Run } from "./run.js";

export interface IrTransmitter {
  send(t: Timings): Promise<void>;
}

/** Sends via the kernel LIRC device with `ir-ctl`, using its pulse/space text format. */
export function irCtlTransmitter(device: string, run: Run = defaultRun): IrTransmitter {
  return {
    async send(t) {
      const dir = fs.mkdtempSync(path.join(os.tmpdir(), "vela-ir-"));
      const file = path.join(dir, "send.txt");
      try {
        fs.writeFileSync(file, t.durationsUs.map((d, i) => `${i % 2 === 0 ? "pulse" : "space"} ${d}\n`).join(""));
        await run("ir-ctl", ["-d", device, `--carrier=${t.carrierHz}`, `--send=${file}`]);
      } finally {
        fs.rmSync(dir, { recursive: true, force: true });
      }
    },
  };
}
