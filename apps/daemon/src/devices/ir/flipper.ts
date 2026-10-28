export type IrSignal =
  | { name: string; type: "parsed"; protocol: string; address: number; command: number }
  | { name: string; type: "raw"; frequency: number; dutyCycle: number; data: number[] };

/** "07 00 00 00" -> 7 (little-endian bytes). */
function leHex(s: string): number {
  return s
    .trim()
    .split(/\s+/)
    .reduce((acc, b, i) => acc + parseInt(b, 16) * 256 ** i, 0);
}

/** Parses a Flipper "IR signals file": header, then blocks separated by lines starting with "#". */
export function parseFlipperIr(text: string): IrSignal[] {
  const lines = text.split(/\r?\n/);
  if (!/^Filetype:\s*IR signals file/i.test(lines[0] ?? "")) throw new Error("Not a Flipper IR signals file");
  const out: IrSignal[] = [];
  let cur: Record<string, string> = {};
  const flush = (): void => {
    if (cur.name === undefined || cur.type === undefined) return void (cur = {});
    if (cur.type === "parsed" && cur.protocol && cur.address && cur.command) {
      out.push({ name: cur.name, type: "parsed", protocol: cur.protocol, address: leHex(cur.address), command: leHex(cur.command) });
    } else if (cur.type === "raw" && cur.frequency && cur.data) {
      out.push({
        name: cur.name,
        type: "raw",
        frequency: Number(cur.frequency),
        dutyCycle: Number(cur.duty_cycle ?? "0.33"),
        data: cur.data.trim().split(/\s+/).map(Number),
      });
    }
    cur = {};
  };
  for (const line of lines.slice(1)) {
    if (line.startsWith("#")) {
      flush();
      continue;
    }
    const i = line.indexOf(":");
    if (i > 0) cur[line.slice(0, i).trim()] = line.slice(i + 1).trim();
  }
  flush();
  return out;
}
