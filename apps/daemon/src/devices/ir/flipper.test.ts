import fs from "node:fs";
import { describe, expect, it } from "vitest";
import { parseFlipperIr } from "./flipper.js";

const fixture = fs.readFileSync(new URL("../../../test/fixtures/samsung.ir", import.meta.url), "utf8");

describe("parseFlipperIr", () => {
  it("parses parsed and raw blocks from fixture", () => {
    const sigs = parseFlipperIr(fixture);
    expect(sigs.map((s) => s.name)).toEqual(["Power", "Vol_up", "Raw_test", "Rc5_test"]);
    expect(sigs[0]).toEqual({ name: "Power", type: "parsed", protocol: "Samsung32", address: 7, command: 2 });
    expect(sigs[2]).toEqual({ name: "Raw_test", type: "raw", frequency: 36000, dutyCycle: 0.33, data: [600, 550, 600, 1650, 600] });
  });

  it("address bytes are little-endian", () => {
    const [s] = parseFlipperIr("Filetype: IR signals file\nVersion: 1\n#\nname: X\ntype: parsed\nprotocol: NECext\naddress: 34 12 00 00\ncommand: 01 02 00 00\n");
    expect(s).toMatchObject({ address: 0x1234, command: 0x0201 });
  });

  it("rejects a file that is not a Flipper IR file", () => {
    expect(() => parseFlipperIr("hello")).toThrow(/Flipper/);
  });
});
