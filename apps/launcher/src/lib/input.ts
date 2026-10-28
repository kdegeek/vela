import type { Direction } from "./focus.js";

export type Action = { kind: "move"; dir: Direction } | { kind: "activate" } | { kind: "back" } | { kind: "home" };

/** Keyboard, remote (CEC/IR arrive as key events) and the X keysyms Chromium reports for them. */
export function actionForKey(key: string): Action | null {
  switch (key) {
    case "ArrowLeft":
      return { kind: "move", dir: "left" };
    case "ArrowRight":
      return { kind: "move", dir: "right" };
    case "ArrowUp":
      return { kind: "move", dir: "up" };
    case "ArrowDown":
      return { kind: "move", dir: "down" };
    case "Enter":
    case " ":
      return { kind: "activate" };
    case "Escape":
    case "Backspace":
    case "BrowserBack":
    case "GoBack":
      return { kind: "back" };
    case "XF86HomePage":
    case "BrowserHome":
    case "GoHome":
      return { kind: "home" };
    default:
      return null;
  }
}

const FIRST_REPEAT_MS = 380;
const REPEAT_MS = 110;

type PadKey = "left" | "right" | "up" | "down" | "a" | "b";
const PAD_ACTIONS: Record<PadKey, Action> = {
  left: { kind: "move", dir: "left" },
  right: { kind: "move", dir: "right" },
  up: { kind: "move", dir: "up" },
  down: { kind: "move", dir: "down" },
  a: { kind: "activate" },
  b: { kind: "back" },
};
const PAD_KEYS = Object.keys(PAD_ACTIONS) as PadKey[];
// Reused every frame so polling allocates nothing.
const pressed: Record<PadKey, boolean> = { left: false, right: false, up: false, down: false, a: false, b: false };

/**
 * Polls the Gamepad API every animation frame while a pad is connected: d-pad or left stick
 * moves (repeating after 380 ms, then every 110 ms), A activates, B goes back. The loop starts
 * on `gamepadconnected` (or at once if a pad is already present) and stops when none remain.
 * Returns a stop function.
 */
export function pollGamepads(onAction: (a: Action) => void): () => void {
  const held: Record<PadKey, boolean> = { left: false, right: false, up: false, down: false, a: false, b: false };
  const next: Record<PadKey, number> = { left: 0, right: 0, up: 0, down: 0, a: 0, b: 0 };
  let raf = 0;

  const tick = (now: number) => {
    for (const k of PAD_KEYS) pressed[k] = false;
    let any = false;
    for (const gp of navigator.getGamepads?.() ?? []) {
      if (!gp) continue;
      any = true;
      const ax = gp.axes[0] ?? 0;
      const ay = gp.axes[1] ?? 0;
      const b = gp.buttons;
      pressed.left ||= b[14]?.pressed === true || ax < -0.5;
      pressed.right ||= b[15]?.pressed === true || ax > 0.5;
      pressed.up ||= b[12]?.pressed === true || ay < -0.5;
      pressed.down ||= b[13]?.pressed === true || ay > 0.5;
      pressed.a ||= b[0]?.pressed === true;
      pressed.b ||= b[1]?.pressed === true;
    }
    if (!any) {
      raf = 0;
      for (const k of PAD_KEYS) held[k] = false;
      return;
    }
    for (const k of PAD_KEYS) {
      if (!pressed[k]) {
        held[k] = false;
        continue;
      }
      const repeatable = k !== "a" && k !== "b";
      if (!held[k]) {
        held[k] = true;
        next[k] = now + FIRST_REPEAT_MS;
        onAction(PAD_ACTIONS[k]);
      } else if (repeatable && now >= next[k]) {
        next[k] = now + REPEAT_MS;
        onAction(PAD_ACTIONS[k]);
      }
    }
    raf = requestAnimationFrame(tick);
  };

  const start = () => {
    if (!raf) raf = requestAnimationFrame(tick);
  };
  addEventListener("gamepadconnected", start);
  if ((navigator.getGamepads?.() ?? []).some((g) => g)) start();
  return () => {
    removeEventListener("gamepadconnected", start);
    cancelAnimationFrame(raf);
    raf = 0;
  };
}
