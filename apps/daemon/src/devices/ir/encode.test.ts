import { describe, expect, it } from "vitest";
import { encode, UnsupportedProtocol } from "./encode.js";

const parsed = (protocol: string, address: number, command: number) => ({ name: "n", type: "parsed" as const, protocol, address, command });

describe("encode", () => {
  it("NEC 0x04/0x08 produces 67 durations starting 9000,4500", () => {
    const t = encode(parsed("NEC", 0x04, 0x08));
    expect(t.carrierHz).toBe(38000);
    expect(t.durationsUs).toHaveLength(67);
    expect(t.durationsUs.slice(0, 2)).toEqual([9000, 4500]);
    // address 0x04, LSB first: 0,0,1,0,0,0,0,0
    expect(t.durationsUs.slice(2, 18)).toEqual([562, 562, 562, 562, 562, 1687, 562, 562, 562, 562, 562, 562, 562, 562, 562, 562]);
    expect(t.durationsUs.at(-1)).toBe(562);
    // ~address 0xFB then command 0x08, ~command 0xF7
    const bits = (arr: number[]) => arr.filter((_, i) => i % 2 === 1).map((d) => (d === 1687 ? 1 : 0));
    const body = bits(t.durationsUs.slice(2, 66));
    const byte = (i: number) => body.slice(i * 8, i * 8 + 8).reduce((a, b, k) => a | (b << k), 0);
    expect([0, 1, 2, 3].map(byte)).toEqual([0x04, 0xfb, 0x08, 0xf7]);
  });

  it("NECext sends the 16-bit address as-is", () => {
    const t = encode(parsed("NECext", 0x1234, 0x08));
    expect(t.durationsUs).toHaveLength(67);
    const body = t.durationsUs.slice(2, 66).filter((_, i) => i % 2 === 1).map((d) => (d === 1687 ? 1 : 0));
    const byte = (i: number) => body.slice(i * 8, i * 8 + 8).reduce((a, b, k) => a | (b << k), 0);
    expect([0, 1, 2, 3].map(byte)).toEqual([0x34, 0x12, 0x08, 0xf7]);
  });

  it("Samsung32 leader is 4500,4500", () => {
    const t = encode(parsed("Samsung32", 0x07, 0x02));
    expect(t.durationsUs.slice(0, 2)).toEqual([4500, 4500]);
    expect(t.durationsUs).toHaveLength(67);
    const body = t.durationsUs.slice(2, 66).filter((_, i) => i % 2 === 1).map((d) => (d === 1687 ? 1 : 0));
    const byte = (i: number) => body.slice(i * 8, i * 8 + 8).reduce((a, b, k) => a | (b << k), 0);
    expect([0, 1, 2, 3].map(byte)).toEqual([0x07, 0x07, 0x02, 0xfd]);
  });

  it("raw passes data through", () => {
    const t = encode({ name: "r", type: "raw", frequency: 36000, dutyCycle: 0.33, data: [1, 2, 3] });
    expect(t).toEqual({ carrierHz: 36000, durationsUs: [1, 2, 3] });
  });

  it("RC5 throws UnsupportedProtocol", () => {
    expect(() => encode(parsed("RC5", 1, 1))).toThrow(UnsupportedProtocol);
    expect(() => encode(parsed("RC5", 1, 1))).toThrow(/RC5/);
  });
});
