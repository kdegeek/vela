import type { Device } from "@vela/shared";

export type CommandIcon = "power" | "power-on" | "power-off" | "volume-down" | "volume-up" | "mute" | "input" | "dot";

export interface CommandButton {
  command: string;
  label: string;
  icon: CommandIcon;
}

const norm = (s: string) => s.toLowerCase().replace(/[-\s]/g, "_");

/** Known commands, in the order they appear on a remote. */
const KNOWN: Record<string, { label: string; icon: CommandIcon }> = {
  power: { label: "Power", icon: "power" },
  power_on: { label: "Turn on", icon: "power-on" },
  power_off: { label: "Turn off", icon: "power-off" },
  volume_down: { label: "Volume down", icon: "volume-down" },
  voldown: { label: "Volume down", icon: "volume-down" },
  vol_down: { label: "Volume down", icon: "volume-down" },
  volume_up: { label: "Volume up", icon: "volume-up" },
  volup: { label: "Volume up", icon: "volume-up" },
  vol_up: { label: "Volume up", icon: "volume-up" },
  mute: { label: "Mute", icon: "mute" },
  input: { label: "Input", icon: "input" },
  source: { label: "Source", icon: "input" },
};
const ORDER = Object.keys(KNOWN);

function humanize(cmd: string): string {
  const words = cmd.replace(/[_-]+/g, " ").trim();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/** The buttons for one device: known commands first in remote order, then the rest as configured. */
export function commandButtons(d: Device): CommandButton[] {
  const rank = (c: string) => {
    const i = ORDER.indexOf(norm(c));
    return i === -1 ? ORDER.length : i;
  };
  return d.commands
    .map((command, i) => ({ command, i }))
    .sort((a, b) => rank(a.command) - rank(b.command) || a.i - b.i)
    .map(({ command }) => {
      const k = KNOWN[norm(command)];
      return { command, label: k?.label ?? humanize(command), icon: k?.icon ?? "dot" };
    });
}
