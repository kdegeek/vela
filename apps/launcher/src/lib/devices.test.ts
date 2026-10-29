import { describe, expect, it } from "vitest";
import { commandButtons } from "./devices.js";

describe("commandButtons", () => {
  it("orders TV commands like a remote: power, volume down, volume up, mute", () => {
    const b = commandButtons({ id: "tv", name: "TV", kind: "tv", commands: ["power_on", "power_off", "volume_up", "volume_down", "mute"] });
    expect(b.map((x) => x.label)).toEqual(["Turn on", "Turn off", "Volume down", "Volume up", "Mute"]);
  });

  it("keeps unknown IR commands in config order after known ones, in sentence case", () => {
    const b = commandButtons({ id: "sb", name: "Soundbar", kind: "ir", commands: ["night_mode", "volup", "power", "bass-boost"] });
    expect(b).toEqual([
      { command: "power", label: "Power", icon: "power" },
      { command: "volup", label: "Volume up", icon: "volume-up" },
      { command: "night_mode", label: "Night mode", icon: "dot" },
      { command: "bass-boost", label: "Bass boost", icon: "dot" },
    ]);
  });
});
