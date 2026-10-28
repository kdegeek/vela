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

/**
 * Polls the Gamepad API every animation frame: d-pad or left stick moves (repeating after
 * 380 ms, then every 110 ms), A activates, B goes back. Returns a stop function.
 */
export function pollGamepads(onAction: (a: Action) => void): () => void {
  const held: Record<string, boolean> = {};
  const next: Record<string, number> = {};
  let raf = 0;
  const tick = (now: number) => {
    const pressed: Record<string, boolean> = {};
    for (const gp of navigator.getGamepads?.() ?? []) {
      if (!gp) continue;
      const ax = gp.axes[0] ?? 0;
      const ay = gp.axes[1] ?? 0;
      const b = (i: number) => gp.buttons[i]?.pressed === true;
      pressed.left ||= b(14) || ax < -0.5;
      pressed.right ||= b(15) || ax > 0.5;
      pressed.up ||= b(12) || ay < -0.5;
      pressed.down ||= b(13) || ay > 0.5;
      pressed.a ||= b(0);
      pressed.b ||= b(1);
    }
    const act: Record<string, Action> = {
      left: { kind: "move", dir: "left" },
      right: { kind: "move", dir: "right" },
      up: { kind: "move", dir: "up" },
      down: { kind: "move", dir: "down" },
      a: { kind: "activate" },
      b: { kind: "back" },
    };
    for (const k of Object.keys(act)) {
      if (!pressed[k]) {
        held[k] = false;
        continue;
      }
      const repeatable = k !== "a" && k !== "b";
      if (!held[k]) {
        held[k] = true;
        next[k] = now + FIRST_REPEAT_MS;
        onAction(act[k]!);
      } else if (repeatable && now >= next[k]!) {
        next[k] = now + REPEAT_MS;
        onAction(act[k]!);
      }
    }
    raf = requestAnimationFrame(tick);
  };
  raf = requestAnimationFrame(tick);
  return () => cancelAnimationFrame(raf);
}
