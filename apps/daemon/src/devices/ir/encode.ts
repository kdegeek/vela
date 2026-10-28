import type { IrSignal } from "./flipper.js";

export interface Timings {
  carrierHz: number;
  /** Alternating pulse, space, pulse, ... in microseconds. */
  durationsUs: number[];
}

export class UnsupportedProtocol extends Error {
  constructor(public readonly protocol: string) {
    super(`IR protocol ${protocol} isn't supported yet.`);
    this.name = "UnsupportedProtocol";
  }
}

const BIT = 562;
const ONE_SPACE = 1687;

function frame(lead: [number, number], bytes: number[]): number[] {
  const d: number[] = [...lead];
  for (const byte of bytes) {
    for (let i = 0; i < 8; i++) d.push(BIT, (byte >> i) & 1 ? ONE_SPACE : BIT); // LSB first
  }
  d.push(BIT);
  return d;
}

export function encode(sig: IrSignal): Timings {
  if (sig.type === "raw") return { carrierHz: sig.frequency, durationsUs: [...sig.data] };
  const a = sig.address;
  const c = sig.command & 0xff;
  switch (sig.protocol) {
    case "NEC":
      return { carrierHz: 38000, durationsUs: frame([9000, 4500], [a & 0xff, ~a & 0xff, c, ~c & 0xff]) };
    case "NECext":
      return { carrierHz: 38000, durationsUs: frame([9000, 4500], [a & 0xff, (a >> 8) & 0xff, c, ~c & 0xff]) };
    case "Samsung32":
      return { carrierHz: 38000, durationsUs: frame([4500, 4500], [a & 0xff, a & 0xff, c, ~c & 0xff]) };
    default:
      throw new UnsupportedProtocol(sig.protocol);
  }
}
