import { execFile } from "node:child_process";

/** Runs a command to completion; rejects on a non-zero exit or spawn failure. */
export type Run = (cmd: string, args: string[]) => Promise<void>;

export const defaultRun: Run = (cmd, args) =>
  new Promise((resolve, reject) => {
    execFile(cmd, args, { timeout: 10_000 }, (err, _out, stderr) => (err ? reject(new Error(`${cmd} failed: ${stderr || err.message}`)) : resolve()));
  });

/** Runs a command and resolves with its stdout. */
export type Capture = (cmd: string, args: string[]) => Promise<string>;

export const defaultCapture: Capture = (cmd, args) =>
  new Promise((resolve, reject) => {
    execFile(cmd, args, { timeout: 10_000, maxBuffer: 16 * 1024 * 1024 }, (err, out, stderr) => (err ? reject(new Error(`${cmd} failed: ${stderr || err.message}`)) : resolve(out)));
  });
