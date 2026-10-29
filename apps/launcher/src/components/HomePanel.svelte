<script lang="ts">
  import { tick } from "svelte";
  import type { Device, HomeStatus } from "@vela/shared";
  import type { Action } from "../lib/input.js";
  import { commandButtons, type CommandIcon } from "../lib/devices.js";

  let {
    open,
    devices,
    status,
    onsend,
    onclose,
  }: {
    open: boolean;
    /** null while loading. */
    devices: Device[] | null;
    status: HomeStatus | null;
    /** Rejects with the daemon's message when the command fails. */
    onsend: (id: string, command: string) => Promise<void>;
    onclose: () => void;
  } = $props();

  const rows = $derived((devices ?? []).map((d) => ({ device: d, buttons: commandButtons(d) })).filter((r) => r.buttons.length));

  let row = $state(0);
  let col = $state(0);
  let sheet = $state<HTMLElement>();
  /** "device/command" while in flight, then briefly marked sent. */
  let busy = $state<Record<string, "sending" | "sent">>({});

  const keyOf = (id: string, cmd: string) => `${id}/${cmd}`;

  async function focusCurrent() {
    await tick();
    sheet?.querySelector<HTMLElement>(`[data-cell="${row}-${col}"]`)?.focus({ preventScroll: true });
  }

  // Each time the sheet opens (or the device list arrives), focus starts on the first button.
  $effect(() => {
    if (!open) return;
    void rows.length;
    row = 0;
    col = 0;
    void focusCurrent();
  });

  async function send(id: string, command: string) {
    const k = keyOf(id, command);
    if (busy[k] === "sending") return;
    busy[k] = "sending";
    try {
      await onsend(id, command);
      busy[k] = "sent";
      setTimeout(() => {
        if (busy[k] === "sent") delete busy[k];
      }, 1400);
    } catch {
      delete busy[k]; // the caller shows the daemon's message
    }
  }

  /** Arrow, Enter and Back handling while the sheet is open. */
  export function handle(a: Action) {
    if (a.kind === "back" || a.kind === "home") return onclose();
    if (a.kind === "activate") {
      const b = rows[row]?.buttons[col];
      const d = rows[row]?.device;
      if (b && d) void send(d.id, b.command);
      return;
    }
    if (a.kind !== "move" || rows.length === 0) return;
    if (a.dir === "left" || a.dir === "right") {
      const n = rows[row]!.buttons.length;
      col = Math.min(n - 1, Math.max(0, col + (a.dir === "right" ? 1 : -1)));
    } else {
      row = Math.min(rows.length - 1, Math.max(0, row + (a.dir === "down" ? 1 : -1)));
      col = Math.min(col, rows[row]!.buttons.length - 1);
    }
    void focusCurrent();
  }

  const ICONS: Record<CommandIcon, string> = {
    power: "M12 3v8M7.2 6.2a7.5 7.5 0 1 0 9.6 0",
    "power-on": "M12 3v8M7.2 6.2a7.5 7.5 0 1 0 9.6 0",
    "power-off": "M7.2 6.2a7.5 7.5 0 1 0 9.6 0M9 12h6",
    "volume-down": "M4 9.5h3.5L12 5.5v13l-4.5-4H4zM16 12h5",
    "volume-up": "M4 9.5h3.5L12 5.5v13l-4.5-4H4zM16 12h5M18.5 9.5v5",
    mute: "M4 9.5h3.5L12 5.5v13l-4.5-4H4zM16 9.5l5 5M21 9.5l-5 5",
    input: "M3.5 5.5h17v11h-17zM8 20h8M10 11h6M13.5 8.5 16 11l-2.5 2.5",
    dot: "M12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 1 0 0-7",
  };
</script>

<div class="veil" class:is-open={open} aria-hidden="true"></div>
<aside class="sheet" class:is-open={open} inert={!open} aria-label="Home" bind:this={sheet}>
  <header class="head">
    <h2 class="title">Home</h2>
    {#if status?.insideTemp || status?.line}
      <p class="summary">
        {#if status?.insideTemp}<span>{status.insideTemp} inside</span>{/if}
        {#if status?.line}<span>{status.line}</span>{/if}
      </p>
    {/if}
  </header>

  <div class="devices">
    {#if devices === null}
      <p class="note">Looking for devices…</p>
    {:else if rows.length === 0}
      <div class="empty">
        <p class="empty-title">No devices yet</p>
        <p class="note">Add your TV or an IR device under [[devices]] in /etc/vela/vela.toml, then restart Vela.</p>
      </div>
    {:else}
      {#each rows as r, ri (r.device.id)}
        <section class="device" class:is-active={ri === row}>
          <h3 class="device-name">
            {r.device.name}
            <span class="via">{r.device.kind === "tv" ? "Home Assistant" : "Infrared"}</span>
          </h3>
          <div class="buttons">
            {#each r.buttons as b, ci (b.command)}
              {@const state = busy[keyOf(r.device.id, b.command)]}
              <button
                class="cmd"
                class:is-sending={state === "sending"}
                class:is-sent={state === "sent"}
                data-cell="{ri}-{ci}"
                onclick={() => {
                  row = ri;
                  col = ci;
                  void send(r.device.id, b.command);
                }}
              >
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d={state === "sent" ? "M5 12.5l4.5 4.5L19 7.5" : ICONS[b.icon]} />
                </svg>
                <span class="label">{b.label}</span>
              </button>
            {/each}
          </div>
        </section>
      {/each}
    {/if}
  </div>

  <footer class="hint" aria-hidden="true"><kbd>Esc</kbd> Back to apps</footer>
</aside>

<style>
  .veil {
    position: absolute;
    inset: 0;
    z-index: 6;
    background: linear-gradient(to left, color-mix(in oklch, var(--abyss) 70%, transparent), color-mix(in oklch, var(--abyss) 35%, transparent));
    opacity: 0;
    pointer-events: none;
  }
  .veil.is-open {
    opacity: 1;
  }

  /* A full-height glass sheet on the inline-end edge. */
  .sheet {
    position: absolute;
    inset-block: 0;
    inset-inline-end: 0;
    z-index: 7;
    inline-size: 50rem;
    display: grid;
    grid-template-rows: auto 1fr auto;
    gap: 3rem;
    padding-block: 5.5rem 3.5rem;
    padding-inline: 4.5rem;
    background:
      radial-gradient(ellipse 90% 45% at 100% 0%, color-mix(in oklch, var(--tint) 16%, transparent), transparent 70%),
      color-mix(in oklch, var(--abyss) 58%, transparent);
    backdrop-filter: blur(2.6rem) saturate(1.5);
    border-inline-start: 0.0625rem solid color-mix(in oklch, var(--starlight) 14%, transparent);
    box-shadow: -2rem 0 6rem -1rem var(--shadow);
    translate: 100% 0;
    visibility: hidden;
  }
  .sheet.is-open {
    translate: 0 0;
    visibility: visible;
  }
  :global(:root[data-effects="lite"]) .sheet {
    backdrop-filter: none;
    background:
      radial-gradient(ellipse 90% 45% at 100% 0%, color-mix(in oklch, var(--tint) 14%, transparent), transparent 70%),
      color-mix(in oklch, var(--abyss) 94%, var(--tint) 6%);
  }

  @media (prefers-reduced-motion: no-preference) {
    .veil {
      transition: opacity 420ms var(--ease-out);
    }
    .sheet {
      transition:
        translate 520ms var(--ease-out),
        visibility 0s linear 520ms;
    }
    .sheet.is-open {
      transition:
        translate 520ms var(--ease-out),
        visibility 0s linear 0s;
    }
  }

  .head {
    display: grid;
    gap: 0.8rem;
  }
  .title {
    font-family: var(--display);
    font-weight: 400;
    font-size: 4.5rem;
    line-height: 0.95;
    letter-spacing: -0.01em;
  }
  .summary {
    display: flex;
    flex-wrap: wrap;
    gap: 0.4rem 1.6rem;
    font-size: 1.15rem;
    font-weight: 600;
    color: var(--mist);
  }

  .devices {
    display: grid;
    align-content: start;
    gap: 2.75rem;
    min-block-size: 0;
  }
  .device {
    display: grid;
    gap: 1.1rem;
    opacity: 0.6;
  }
  .device.is-active {
    opacity: 1;
  }
  @media (prefers-reduced-motion: no-preference) {
    .device {
      transition: opacity 300ms var(--ease-out);
    }
  }
  .device-name {
    margin: 0;
    display: flex;
    align-items: baseline;
    gap: 1rem;
    font-size: 1.3rem;
    font-weight: 650;
  }
  .via {
    font-size: 0.95rem;
    font-weight: 600;
    color: var(--faint);
  }
  /* Five 7 rem buttons fill one row of the sheet; longer IR lists wrap. */
  .buttons {
    display: flex;
    flex-wrap: wrap;
    gap: 1rem;
  }

  .cmd {
    inline-size: 7rem;
    aspect-ratio: 1;
    display: grid;
    place-content: center;
    justify-items: center;
    gap: 0.75rem;
    border-radius: 1.4rem;
    background: color-mix(in oklch, var(--starlight) 8%, transparent);
    border: 0.0625rem solid color-mix(in oklch, var(--starlight) 12%, transparent);
    color: var(--starlight);
  }
  .cmd svg {
    inline-size: 2.3rem;
    fill: none;
    stroke: currentColor;
    stroke-width: 1.6;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
  .label {
    font-size: 0.95rem;
    font-weight: 650;
    line-height: 1.15;
    text-align: center;
  }
  .cmd:focus-visible {
    outline: 0.22rem solid var(--starlight);
    outline-offset: 0.3rem;
    scale: 1.06;
    background: var(--starlight);
    color: var(--abyss);
    box-shadow: 0 0 3rem 0.25rem color-mix(in oklch, var(--tint) 45%, transparent);
  }
  .cmd.is-sent svg {
    stroke: var(--tint);
  }
  .cmd.is-sent:focus-visible svg {
    stroke: currentColor;
  }
  @media (prefers-reduced-motion: no-preference) {
    .cmd {
      transition:
        scale 220ms var(--ease-out),
        background-color 220ms var(--ease-out),
        color 220ms var(--ease-out),
        box-shadow 320ms var(--ease-out);
    }
    .cmd.is-sending svg {
      animation: pulse 900ms var(--ease-out) infinite alternate;
    }
  }
  @keyframes pulse {
    to {
      opacity: 0.35;
    }
  }

  .empty {
    display: grid;
    gap: 0.8rem;
    max-inline-size: 36rem;
  }
  .empty-title {
    font-size: 1.6rem;
    font-weight: 650;
  }
  .note {
    font-size: 1.2rem;
    line-height: 1.5;
    color: var(--mist);
  }

  .hint {
    font-size: 0.95rem;
    font-weight: 600;
    color: var(--faint);
  }
  kbd {
    display: inline-block;
    min-inline-size: 1.5rem;
    margin-inline-end: 0.4rem;
    padding-inline: 0.35rem;
    border: 0.0625rem solid var(--faint);
    border-radius: 0.3rem;
    font-family: inherit;
    font-size: 0.8rem;
    line-height: 1.4rem;
    text-align: center;
  }
</style>
