import fs from "node:fs";
import { describe, expect, it } from "vitest";
import { irCtlTransmitter } from "./irctl.js";

describe("irCtlTransmitter", () => {
  it("writes pulse/space lines and calls ir-ctl with carrier", async () => {
    let seen: { cmd: string; args: string[]; file: string } | undefined;
    const run = async (cmd: string, args: string[]) => {
      const file = args.find((a) => a.startsWith("--send="))!.slice("--send=".length);
      seen = { cmd, args, file: fs.readFileSync(file, "utf8") };
    };
    await irCtlTransmitter("/dev/lirc0", run).send({ carrierHz: 38000, durationsUs: [9000, 4500, 562] });
    expect(seen!.cmd).toBe("ir-ctl");
    expect(seen!.args).toEqual(expect.arrayContaining(["-d", "/dev/lirc0", "--carrier=38000"]));
    expect(seen!.file).toBe("pulse 9000\nspace 4500\npulse 562\n");
  });
});
