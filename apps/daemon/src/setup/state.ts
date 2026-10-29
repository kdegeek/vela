import fs from "node:fs";
import type { SetupKey, SetupProgress, SetupStatus, SetupStep } from "@vela/shared";
import { PinGuard } from "./pin.js";

/** A refusal the phone shows as is: `status` is the HTTP code, the message says what happened and what to do. */
export class SetupError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}

/** One run of setup mode: the PIN and the answers so far. onChange fires on anything the TV shows. */
export class SetupSession {
  readonly pin: PinGuard;
  private media: SetupProgress["media"] = null;
  private keys: SetupKey[] = [];
  private sshSkipped = false;
  private finished = false;

  constructor(private onChange: () => void = () => {}, now?: () => number) {
    this.pin = new PinGuard(onChange, now);
  }

  step(): SetupStep {
    if (this.finished) return "committing";
    if (!this.media) return "media";
    if (!this.keys.length && !this.sshSkipped) return "ssh";
    return "done";
  }

  status(url: string | null): SetupStatus {
    const server = this.media?.provider === "jellyfin" ? this.media.server : undefined;
    return { pin: this.pin.current(), url, step: this.step(), ...(server ? { jellyfinServer: server } : {}), keyCount: this.keys.length };
  }

  progress(): SetupProgress {
    return { step: this.step(), media: this.media, keys: this.keys, sshSkipped: this.sshSkipped };
  }

  // Any answer reopens a finished session, so a commit root refused can be fixed and retried from the phone.
  private changed(): void {
    this.finished = false;
    this.onChange();
  }

  setMedia(media: NonNullable<SetupProgress["media"]>): void {
    this.media = media;
    this.changed();
  }

  setKeys(keys: SetupKey[]): void {
    this.keys = keys;
    this.sshSkipped = false;
    this.changed();
  }

  skipSsh(): void {
    this.keys = [];
    this.sshSkipped = true;
    this.changed();
  }

  /** What /var/lib/vela/setup gets; throws if a step is unanswered. */
  answers(): { media: NonNullable<SetupProgress["media"]>; keys: SetupKey[] } {
    if (!this.media) throw new SetupError(409, "Choose a media server or skip that step first.");
    if (!this.keys.length && !this.sshSkipped) throw new SetupError(409, "Add an SSH key or skip that step first.");
    return { media: this.media, keys: this.keys };
  }

  markFinished(): void {
    this.finished = true;
    this.onChange();
  }
}

/**
 * Polls for the setup gate (/run/vela-setup/open, created and removed only by root) and calls onChange(true/false)
 * when it appears or disappears, starting with the current state. Polling, because /run/vela-setup may not exist
 * yet. Each tick also calls onTick so the caller can notice a new IP address.
 */
export function watchGate(gatePath: string, onChange: (open: boolean) => void, onTick: () => void = () => {}, intervalMs = 1000) {
  let open: boolean | undefined;
  const tick = () => {
    const now = fs.existsSync(gatePath);
    if (now !== open) {
      open = now;
      onChange(now);
    }
    onTick();
  };
  tick();
  const timer = setInterval(tick, intervalMs);
  timer.unref();
  return { tick, stop: () => clearInterval(timer) };
}
