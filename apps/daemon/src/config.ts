import fs from "node:fs";
import { parse } from "smol-toml";
import { z } from "zod";

const IrRef = z.object({ file: z.string(), button: z.string() });

const Device = z.discriminatedUnion("kind", [
  z.object({
    id: z.string(),
    name: z.string(),
    kind: z.literal("tv"),
    entity: z.string(),
    powerOnFallback: IrRef.optional(),
  }),
  z.object({
    id: z.string(),
    name: z.string(),
    kind: z.literal("ir"),
    transmitter: z.enum(["irctl", "audiojack"]),
    codes: z.record(z.string(), IrRef),
  }),
]);

const ConfigSchema = z.object({
  server: z.object({ listen: z.string().default("127.0.0.1") }).default({ listen: "127.0.0.1" }),
  ui: z
    .object({
      effects: z.enum(["auto", "full", "lite"]).default("auto"),
      clock24h: z.boolean().default(false),
    })
    .default({ effects: "auto", clock24h: false }),
  media: z
    .object({
      provider: z.enum(["jellyfin", "mock"]).default("mock"),
      jellyfin: z.object({ url: z.string(), username: z.string() }).optional(),
    })
    .default({ provider: "mock" }),
  homeAssistant: z
    .object({ url: z.string(), insideTempEntity: z.string().optional() })
    .optional(),
  devices: z.array(Device).default([]),
  manifestDirs: z.array(z.string()).default(["/usr/share/vela/apps", "/etc/vela/apps"]),
});

export type VelaConfig = z.infer<typeof ConfigSchema>;
export type DeviceConfig = z.infer<typeof Device>;
export type IrRef = z.infer<typeof IrRef>;

export interface Secrets {
  jellyfinToken?: string;
  jellyfinUserId?: string;
  homeAssistantToken?: string;
}

/** Missing file gives defaults; an invalid file throws an error naming the offending field path. */
export function loadConfig(path: string): VelaConfig {
  let raw: unknown = {};
  try {
    raw = parse(fs.readFileSync(path, "utf8"));
  } catch (e) {
    if ((e as NodeJS.ErrnoException).code !== "ENOENT") throw new Error(`Cannot read ${path}: ${(e as Error).message}`);
  }
  const r = ConfigSchema.safeParse(raw);
  if (!r.success) {
    const msg = r.error.issues.map((i) => `${i.path.join(".") || "(root)"}: ${i.message}`).join("; ");
    throw new Error(`Invalid config ${path}: ${msg}`);
  }
  return r.data;
}

export function loadSecrets(path: string): Secrets {
  try {
    return JSON.parse(fs.readFileSync(path, "utf8")) as Secrets;
  } catch (e) {
    if ((e as NodeJS.ErrnoException).code === "ENOENT") return {};
    throw e;
  }
}

export function saveSecrets(path: string, s: Secrets): void {
  // Write to a 0600 temp file then rename, so the secrets are never readable at a looser mode.
  const tmp = `${path}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(s, null, 2), { mode: 0o600 });
  fs.chmodSync(tmp, 0o600);
  fs.renameSync(tmp, path);
}
