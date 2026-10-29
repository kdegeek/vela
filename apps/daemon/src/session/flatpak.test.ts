import { describe, expect, it } from "vitest";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { flatpakStatus, readFlatpakList } from "./flatpak.js";

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

describe("readFlatpakList", () => {
  it("reads ids, skipping comments and blank lines; a missing file is empty", () => {
    const p = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "vela-fl-")), "flatpaks.list");
    fs.writeFileSync(p, "# first boot\ncom.google.Chrome\n\n  tv.plex.PlexHTPC  # Plex\n");
    expect([...readFlatpakList(p)]).toEqual(["com.google.Chrome", "tv.plex.PlexHTPC"]);
    expect(readFlatpakList(p + ".nope").size).toBe(0);
  });

  it("the shipped list names every built-in flatpak app", () => {
    const shipped = readFlatpakList(new URL("../../../../os/files/usr/share/vela/flatpaks.list", import.meta.url).pathname);
    for (const id of ["com.google.Chrome", "tv.plex.PlexHTPC", "com.github.iwalton3.jellyfin-media-player", "com.valvesoftware.Steam"]) expect(shipped.has(id)).toBe(true);
  });
});
