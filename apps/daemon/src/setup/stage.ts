import fs from "node:fs";
import path from "node:path";
import { stringify } from "smol-toml";
import type { SetupKey, SetupProgress } from "@vela/shared";
import { configProblem } from "../config.js";

/** The vela.toml setup writes: just the media choice; everything else keeps its default. */
export function setupToml(media: NonNullable<SetupProgress["media"]>): string {
  const doc = media.provider === "jellyfin" ? { media: { provider: "jellyfin", jellyfin: { url: media.url } } } : { media: { provider: "mock" } };
  return `# Written by Vela setup. See docs/install.md for the other settings.\n${stringify(doc)}\n`;
}

function writeAtomic(file: string, text: string): void {
  const tmp = `${file}.tmp`;
  fs.writeFileSync(tmp, text, { mode: 0o600 });
  fs.renameSync(tmp, file);
}

/**
 * Stages root's files for vela-setup-commit (root): `vela.toml`, `authorized_keys` (only when keys were added), then
 * touches `commit` last, so the helper never sees a half-written stage. A stale `commit` is removed first.
 */
export function stageAndCommit(dir: string, answers: { media: NonNullable<SetupProgress["media"]>; keys: SetupKey[] }): void {
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  const commit = path.join(dir, "commit");
  const keysFile = path.join(dir, "authorized_keys");
  fs.rmSync(commit, { force: true });
  const toml = setupToml(answers.media);
  const problem = configProblem(toml);
  if (problem) throw new Error(`setup wrote a vela.toml its own schema rejects: ${problem}`);
  writeAtomic(path.join(dir, "vela.toml"), toml);
  if (answers.keys.length) writeAtomic(keysFile, answers.keys.map((k) => `${k.key}\n`).join(""));
  else fs.rmSync(keysFile, { force: true });
  fs.writeFileSync(commit, "", { mode: 0o600 });
}
