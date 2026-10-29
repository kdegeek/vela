import { describe, expect, it } from "vitest";
import type { HomeState, SetupStatus } from "@vela/shared";
import { formatPin, nextSetup, setupView, splitAddress, stepsFor } from "./setup.js";

const status = (s: Partial<SetupStatus> = {}): SetupStatus => ({ pin: "482913", url: "http://192.168.1.42:7301/", step: "media", keyCount: 0, ...s });
const home = (setup?: SetupStatus): HomeState => ({ apps: [], rows: [], status: {}, ...(setup ? { setup } : {}) });

describe("setup screen state", () => {
  it("shows while /api/home carries setup and follows the event stream", () => {
    let s = nextSetup(null, { home: home(status()) });
    expect(s?.step).toBe("media");
    s = nextSetup(s, { event: { type: "setup", setup: status({ step: "ssh", jellyfinServer: "Den" }) } });
    expect(s).toMatchObject({ step: "ssh", jellyfinServer: "Den" });
    // Unrelated events keep it.
    expect(nextSetup(s, { event: { type: "home-state-changed" } })).toBe(s);
    expect(nextSetup(s, { event: { type: "toast", message: "hi" } })).toBe(s);
    // Setup ends: back to home.
    expect(nextSetup(s, { event: { type: "setup", setup: null } })).toBeNull();
    expect(nextSetup(s, { home: home() })).toBeNull();
  });

  it("encodes the address with the PIN in the fragment and shows both readably", () => {
    expect(setupView(status())).toMatchObject({
      kind: "ready",
      address: { scheme: "http://", host: "192.168.1.42:7301" },
      pin: "482 913",
      qrText: "http://192.168.1.42:7301/#pin=482913",
    });
  });

  it("asks for Ethernet while the box has no address", () => {
    expect(setupView(status({ url: null })).kind).toBe("offline");
  });

  it("explains a missing PIN rather than showing a code that leads nowhere", () => {
    const { pin: _, ...noPin } = status();
    const v = setupView(noPin);
    expect(v.kind).toBe("no-pin");
    expect(v).not.toHaveProperty("qrText");
  });

  it("shows the timed-out screen when setup expired", () => {
    expect(setupView({ expired: true, url: null, step: "media", keyCount: 0 })).toEqual({ kind: "expired" });
  });

  it("walks the steps: media, SSH access, finishing, with what was chosen", () => {
    expect(stepsFor(status()).map((s) => s.state)).toEqual(["current", "pending", "pending"]);
    const ssh = stepsFor(status({ step: "ssh", jellyfinServer: "Living room" }));
    expect(ssh.map((s) => s.state)).toEqual(["done", "current", "pending"]);
    expect(ssh[0]!.detail).toBe("Living room on Jellyfin");
    expect(stepsFor(status({ step: "ssh" }))[0]!.detail).toBe("Sample library for now");
    const done = stepsFor(status({ step: "done", keyCount: 2 }));
    expect(done.map((s) => s.state)).toEqual(["done", "done", "current"]);
    expect(done[1]!.detail).toBe("2 keys added");
    expect(stepsFor(status({ step: "done", keyCount: 1 }))[1]!.detail).toBe("1 key added");
    expect(stepsFor(status({ step: "done" }))[1]!.detail).toBe("Skipped");
    expect(stepsFor(status({ step: "committing", keyCount: 1 }))[2]).toEqual({ label: "Finishing", state: "current", detail: "Saving your settings" });
  });

  it("formats addresses and PINs defensively", () => {
    expect(splitAddress("http://10.0.0.7:7301/")).toEqual({ scheme: "http://", host: "10.0.0.7:7301" });
    expect(formatPin("12345")).toBe("12345");
  });
});
