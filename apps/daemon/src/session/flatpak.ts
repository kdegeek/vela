import fs from "node:fs";
import { execFile } from "node:child_process";

const INSTALLING_FILE = "/var/lib/vela/flatpak-installing";

function defaultRun(cmd: string[]): Promise<string> {
  return new Promise((resolve, reject) => {
    const [bin = "", ...args] = cmd;
    execFile(bin, args, (err, stdout) => (err ? reject(err) : resolve(stdout)));
  });
}

/** ready if `flatpak info <id>` succeeds; installing if the installer's marker file lists the id; else missing. */
export async function flatpakStatus(
  id: string,
  run: (cmd: string[]) => Promise<string> = defaultRun,
  installingFile: string = INSTALLING_FILE,
): Promise<"ready" | "installing" | "missing"> {
  try {
    await run(["flatpak", "info", id]);
    return "ready";
  } catch {
    // not installed; fall through
  }
  try {
    const ids = fs.readFileSync(installingFile, "utf8").split(/\s+/).filter(Boolean);
    if (ids.includes(id)) return "installing";
  } catch {
    // no marker file
  }
  return "missing";
}
