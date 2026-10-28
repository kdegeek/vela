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
  if (sig.type === "raw") {
    const d = sig.data;
    if (!Number.isFinite(sig.frequency) || sig.frequency <= 0) throw new Error(`Raw IR signal ${sig.name} has an invalid carrier frequency.`);
    if (d.length === 0 || !d.every((n) => Number.isInteger(n) && n > 0)) {
      throw new Error(`Raw IR signal ${sig.name} has invalid timing data. Every duration must be a positive whole number.`);
    }
    // A burst must end on a pulse; drop a trailing space.
    return { carrierHz: sig.frequency, durationsUs: d.length % 2 === 0 ? d.slice(0, -1) : [...d] };
  }
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
