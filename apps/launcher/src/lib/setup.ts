import type { HomeState, SetupStatus, VelaEvent } from "@vela/shared";

/** What the setup screen shows, decided from the daemon's SetupStatus. */
export type SetupView =
  | { kind: "ready"; address: Address; pin: string; qrText: string; steps: StepView[] }
  /** The box has no IPv4 address yet: no code to show. */
  | { kind: "offline"; steps: StepView[] }
  /** The main API listens beyond loopback, so the daemon leaves the PIN out. */
  | { kind: "no-pin"; address: Address; steps: StepView[] }
  /** The 2-hour window closed unfinished. */
  | { kind: "expired" };

export interface Address {
  /** `http://`, shown quieter than the rest. */
  scheme: string;
  /** `192.168.1.42:7301`: what the owner types. */
  host: string;
}

export interface StepView {
  label: string;
  state: "done" | "current" | "pending";
  detail?: string;
}

export function splitAddress(url: string): Address {
  const m = /^(https?:\/\/)(.*?)\/?$/.exec(url);
  return m ? { scheme: m[1]!, host: m[2]! } : { scheme: "", host: url };
}

/** `482913` → `482 913`: two groups of three read aloud and copy more easily. */
export function formatPin(pin: string): string {
  return /^\d{6}$/.test(pin) ? `${pin.slice(0, 3)} ${pin.slice(3)}` : pin;
}

const plural = (n: number, one: string) => `${n} ${one}${n === 1 ? "" : "s"}`;

/** Media → SSH access → Finishing, with what the phone has answered so far. */
export function stepsFor(s: SetupStatus): StepView[] {
  const order = ["media", "ssh", "done", "committing"] as const;
  const at = order.indexOf(s.step);
  const media: StepView = at === 0 ? { label: "Media", state: "current", detail: "Choose a Jellyfin server" } : { label: "Media", state: "done", detail: s.jellyfinServer ? `${s.jellyfinServer} on Jellyfin` : "Sample library for now" };
  const ssh: StepView =
    at < 1
      ? { label: "SSH access", state: "pending" }
      : at === 1
        ? { label: "SSH access", state: "current", detail: "Add a key from GitHub or paste one" }
        : { label: "SSH access", state: "done", detail: s.keyCount ? `${plural(s.keyCount, "key")} added` : "Skipped" };
  const finish: StepView =
    at < 2 ? { label: "Finishing", state: "pending" } : at === 2 ? { label: "Finishing", state: "current", detail: "Tap Finish on your phone" } : { label: "Finishing", state: "current", detail: "Saving your settings" };
  return [media, ssh, finish];
}

export function setupView(s: SetupStatus): SetupView {
  if (s.expired) return { kind: "expired" };
  const steps = stepsFor(s);
  if (s.url === null) return { kind: "offline", steps };
  const address = splitAddress(s.url);
  if (!s.pin) return { kind: "no-pin", address, steps };
  return { kind: "ready", address, pin: formatPin(s.pin), qrText: `${s.url}#pin=${s.pin}`, steps };
}

/**
 * The setup screen's state: `/api/home` carries it (`setup`, absent outside setup mode) and the event stream updates it
 * (`{type: "setup"}`, null when setup ends). Other events leave it alone.
 */
export function nextSetup(current: SetupStatus | null, from: { home: HomeState } | { event: VelaEvent }): SetupStatus | null {
  if ("home" in from) return from.home.setup ?? null;
  return from.event.type === "setup" ? from.event.setup : current;
}
