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

/** The parser's own words, never the file's text: smol-toml and V8 quote the offending bytes in their messages. */
function tomlProblem(e: unknown): string {
  const t = e as { line?: number; column?: number; message?: string };
  const first = (t.message ?? "").split("\n")[0]!.replace(/^Invalid TOML document:\s*/, "").trim();
  // smol-toml's first line is a fixed description; drop anything quoted, just in case.
  const what = first.replace(/(["'`]).*?\1/g, "…") || "not valid TOML";
  return typeof t.line === "number" ? `line ${t.line}, column ${t.column ?? 0}: ${what}` : what;
}

function jsonProblem(e: unknown): string {
  const m = /line (\d+) column (\d+)/.exec((e as Error).message ?? "");
  return m ? `not valid JSON (line ${m[1]}, column ${m[2]})` : "not valid JSON";
}

/** Missing file gives defaults; an invalid file throws an error naming the path and the offending field path, never its contents. */
export function loadConfig(path: string): VelaConfig {
  let text: string | undefined;
  try {
    text = fs.readFileSync(path, "utf8");
  } catch (e) {
    if ((e as NodeJS.ErrnoException).code !== "ENOENT") throw new Error(`Cannot read ${path}: ${(e as NodeJS.ErrnoException).code ?? "read error"}`);
  }
  let raw: unknown = {};
  if (text !== undefined) {
    try {
      raw = parse(text);
    } catch (e) {
      throw new Error(`Invalid config ${path}: ${tomlProblem(e)}`);
    }
  }
  const r = ConfigSchema.safeParse(raw);
  if (!r.success) {
    const msg = r.error.issues.map((i) => `${i.path.join(".") || "(root)"}: ${i.message}`).join("; ");
    throw new Error(`Invalid config ${path}: ${msg}`);
  }
  return r.data;
}

/** Missing file gives {}; a malformed one throws an error naming the path, never its contents. */
export function loadSecrets(path: string): Secrets {
  let text: string;
  try {
    text = fs.readFileSync(path, "utf8");
  } catch (e) {
    const code = (e as NodeJS.ErrnoException).code;
    if (code === "ENOENT") return {};
    throw new Error(`Cannot read ${path}: ${code ?? "read error"}`);
  }
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch (e) {
    throw new Error(`Invalid secrets ${path}: ${jsonProblem(e)}`);
  }
  if (typeof raw !== "object" || raw === null || Array.isArray(raw)) throw new Error(`Invalid secrets ${path}: not a JSON object`);
  return raw as Secrets;
}

export interface Settings {
  config: VelaConfig;
  secrets: Secrets;
  /** Set when a file could not be used; shown as the home screen's status line. */
  problem?: string;
}

const problemLine = (path: string, e: unknown) => {
  const m = (e as Error).message;
  const detail = m.replace(/^(Invalid config|Invalid secrets|Cannot read) [^:]*: /, "").replace(/\.+$/, "");
  return `Vela couldn't read ${path}: ${detail}. Fix the file and restart.`;
};

/**
 * Never throws, so a typo can't crash-loop the daemon into a dead screen. An unusable vela.toml
 * gives the defaults (mock media, no devices); an unusable secrets.json gives no secrets.
 */
export function loadSettings(configPath: string, secretsPath: string): Settings {
  const problems: string[] = [];
  let config: VelaConfig;
  try {
    config = loadConfig(configPath);
  } catch (e) {
    console.error("vela:", (e as Error).message);
    problems.push(problemLine(configPath, e));
    config = ConfigSchema.parse({});
  }
  let secrets: Secrets = {};
  try {
    secrets = loadSecrets(secretsPath);
  } catch (e) {
    console.error("vela:", (e as Error).message);
    problems.push(problemLine(secretsPath, e));
  }
  return { config, secrets, problem: problems.length ? problems.join(" ") : undefined };
}

export function saveSecrets(path: string, s: Secrets): void {
  // Write to a 0600 temp file then rename, so the secrets are never readable at a looser mode.
  const tmp = `${path}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(s, null, 2), { mode: 0o600 });
  fs.chmodSync(tmp, 0o600);
  fs.renameSync(tmp, path);
}
