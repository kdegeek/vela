import { describe, expect, it } from "vitest";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { flatpakStatus } from "./flatpak.js";

const tmpFile = (content?: string) => {
  const p = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "vela-fp-")), "flatpak-installing");
  if (content !== undefined) fs.writeFileSync(p, content);
  return p;
};

describe("flatpakStatus", () => {
  it("info success -> ready", async () => {
    expect(await flatpakStatus("a.b", async () => "ok", tmpFile())).toBe("ready");
  });
  it("listed in installing file -> installing", async () => {
    const run = async () => { throw new Error("not installed"); };
    expect(await flatpakStatus("a.b", run, tmpFile("x.y\na.b\n"))).toBe("installing");
  });
  it("neither -> missing", async () => {
    const run = async () => { throw new Error("not installed"); };
    expect(await flatpakStatus("a.b", run, tmpFile("x.y\n"))).toBe("missing");
    expect(await flatpakStatus("a.b", run, tmpFile())).toBe("missing");
  });
});
