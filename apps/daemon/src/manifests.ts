import fs from "node:fs";
import path from "node:path";
import { z } from "zod";
import type { HeroItem } from "@vela/shared";

const Manifest = z.object({
  id: z.string().min(1),
  name: z.string().min(1),
  glyph: z.string().min(1),
  brandColor: z.string().min(1),
  kind: z.enum(["flatpak", "command", "builtin"]),
  flatpak: z.string().optional(),
  args: z.array(z.string()).optional(),
  command: z.array(z.string()).optional(),
  windowAppId: z.string().min(1),
  homePassthrough: z.boolean().optional(),
  /** Position in the Apps row; lower first. Apps without one sort last, by name. */
  order: z.number().optional(),
  feature: z
    .discriminatedUnion("kind", [
      z.object({ kind: z.literal("mock"), slug: z.string() }),
      z.object({ kind: z.literal("jellyfin-resume") }),
      z.object({ kind: z.literal("static"), item: z.custom<Omit<HeroItem, "sourceId">>((v) => typeof v === "object" && v !== null) }),
    ])
    .optional(),
});

export type AppManifest = z.infer<typeof Manifest>;

/** Later dirs override earlier ones by id. Invalid files are skipped with a logged error. */
export function loadManifests(dirs: string[]): AppManifest[] {
  const byId = new Map<string, AppManifest>();
  for (const dir of dirs) {
    let files: string[];
    try {
      files = fs.readdirSync(dir).filter((f) => f.endsWith(".json")).sort();
    } catch {
      continue; // directory absent
    }
    for (const f of files) {
      const p = path.join(dir, f);
      try {
        const m = Manifest.parse(JSON.parse(fs.readFileSync(p, "utf8")));
        if (m.kind === "flatpak" && !m.flatpak) throw new Error("kind=flatpak requires flatpak");
        if (m.kind === "command" && !m.command?.length) throw new Error("kind=command requires command");
        byId.set(m.id, m);
      } catch (e) {
        console.error(`vela: skipping manifest ${p}: ${(e as Error).message}`);
      }
    }
  }
  return [...byId.values()];
}
