<script lang="ts">
  import type { SetupStatus } from "@vela/shared";
  import { encodeQr } from "../lib/qr.js";
  import { setupView } from "../lib/setup.js";

  let { status }: { status: SetupStatus } = $props();

  const view = $derived(setupView(status));
  const qr = $derived(view.kind === "ready" ? encodeQr(view.qrText) : null);

  // A fixed scatter of faint stars: seeded, so the sky never shifts between renders or screenshots.
  const stars = (() => {
    let seed = 7;
    const rand = () => ((seed = (seed * 16807) % 2147483647) - 1) / 2147483646;
    return Array.from({ length: 80 }, () => ({ x: rand() * 1200, y: rand() * 675, r: 0.5 + rand() * 1.2, o: 0.08 + rand() * 0.3 }));
  })();

  // Broken where a reader pauses.
  const headline = $derived(
    view.kind === "expired" ? ["Setup timed out"] : view.kind === "offline" ? ["Connect Ethernet", "to continue"] : ["Set up Vela", "with your phone"],
  );
</script>

<section class="setup" aria-live="polite">
  <svg class="sky" viewBox="0 0 1200 675" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
    {#each stars as s, i (i)}
      <circle cx={s.x} cy={s.y} r={s.r} fill="currentColor" opacity={s.o} />
    {/each}
  </svg>

  <div class="copy">
    <h1>
      {#each headline as line, i (i)}{#if i}<br />{/if}{line}{/each}
    </h1>

    {#if view.kind === "ready"}
      <p class="lede">Point your phone's camera at the code. Or open the address below in its browser and enter the PIN.</p>
      <dl class="credentials">
        <div>
          <dt>Address</dt>
          <dd class="address"><span class="scheme">{view.address.scheme}</span>{view.address.host}</dd>
        </div>
        <div>
          <dt>PIN</dt>
          <dd class="pin">{view.pin}</dd>
        </div>
      </dl>
    {:else if view.kind === "no-pin"}
      <p class="lede">
        The setup PIN is hidden because Vela's API listens beyond this box, where others could read it. Set
        <code>server.listen</code> to <code>127.0.0.1</code> in /etc/vela/vela.toml and restart Vela to show it here.
      </p>
      <dl class="credentials">
        <div>
          <dt>Address</dt>
          <dd class="address"><span class="scheme">{view.address.scheme}</span>{view.address.host}</dd>
        </div>
      </dl>
    {:else if view.kind === "offline"}
      <p class="lede">Vela sets up over your home network. Plug a network cable into the box; the code for your phone appears here as soon as it's connected.</p>
    {:else}
      <p class="lede">Setup stays open for 2 hours. Restart the box to open it again, or run <code>vela-ctl setup</code> over SSH.</p>
    {/if}
  </div>

  <div class="tile" class:glass={!qr}>
    {#if qr}
      <svg class="code" viewBox="0 0 {qr.size} {qr.size}" shape-rendering="crispEdges" role="img" aria-label="QR code for the setup address">
        <path d={qr.path} />
      </svg>
    {:else}
      <div class="placeholder">
        {#if view.kind === "offline"}
          <svg viewBox="0 0 48 48" aria-hidden="true">
            <path d="M10 14h28v20H31v5H17v-5h-7z" />
            <path d="M17 21v6M22 21v6M26 21v6M31 21v6" />
          </svg>
          <p>Waiting for a network connection</p>
        {:else if view.kind === "no-pin"}
          <svg viewBox="0 0 48 48" aria-hidden="true">
            <rect x="12" y="21" width="24" height="17" rx="3" />
            <path d="M17 21v-5a7 7 0 0 1 14 0v5" />
          </svg>
          <p>PIN hidden on this network setting</p>
        {:else}
          <svg viewBox="0 0 48 48" aria-hidden="true">
            <circle cx="24" cy="24" r="15" />
            <path d="M24 15v9l6 4" />
          </svg>
          <p>Setup is closed</p>
        {/if}
      </div>
    {/if}
  </div>

  {#if view.kind !== "expired"}
    <ol class="steps" aria-label="Setup progress">
      {#each view.steps as step, i (step.label)}
        <li class={step.state} aria-current={step.state === "current" ? "step" : undefined}>
          <span class="star" aria-hidden="true"></span>
          <span class="label"><span class="n">{i + 1}</span>{step.label}</span>
          {#if step.detail}<span class="detail">{step.detail}</span>{/if}
        </li>
      {/each}
    </ol>
  {/if}
</section>

<style>
  .setup {
    position: absolute;
    inset: 0;
    z-index: 1;
    display: grid;
    grid-template-columns: 1fr 36rem;
    grid-template-rows: 1fr auto;
    column-gap: 10rem;
    padding: 10rem var(--gutter) 6rem;
    background:
      radial-gradient(ellipse 46% 62% at 80% 42%, color-mix(in oklch, var(--nebula) 85%, transparent), transparent 72%),
      radial-gradient(ellipse 60% 50% at 6% 104%, color-mix(in oklch, oklch(0.62 0.1 285) 22%, transparent), transparent 70%),
      var(--abyss);
    overflow: clip;
  }

  .sky {
    position: absolute;
    inset: 0;
    inline-size: 100%;
    block-size: 100%;
    color: var(--starlight);
    pointer-events: none;
  }

  /* ---------- Copy ---------- */

  .copy {
    position: relative;
    align-self: center;
    display: grid;
    gap: 2.4rem;
    max-inline-size: 60rem;
  }
  h1 {
    font-family: var(--display);
    font-weight: 400;
    font-size: 6.6rem;
    line-height: 1;
    letter-spacing: -0.015em;
  }
  .lede {
    font-size: 1.75rem;
    line-height: 1.5;
    color: var(--mist);
    max-inline-size: 40ch;
    text-wrap: pretty;
  }
  code {
    font-family: var(--ui);
    font-weight: 600;
    color: var(--starlight);
    padding: 0.05em 0.4em;
    border-radius: 0.4em;
    background: color-mix(in oklch, var(--starlight) 10%, transparent);
  }

  .credentials {
    display: flex;
    flex-wrap: wrap;
    align-items: end;
    gap: 2rem 5rem;
    margin: 0.8rem 0 0;
  }
  .credentials div {
    display: grid;
    gap: 0.5rem;
  }
  dt {
    font-size: 1.25rem;
    font-weight: 600;
    color: var(--faint);
  }
  dd {
    margin: 0;
    font-variant-numeric: tabular-nums;
  }
  .address {
    font-size: 2.6rem;
    font-weight: 500;
    line-height: 1.25;
    letter-spacing: 0.01em;
  }
  .scheme {
    color: var(--faint);
  }
  .pin {
    font-size: 5.25rem;
    font-weight: 300;
    line-height: 1;
    letter-spacing: 0.08em;
    white-space: pre;
  }

  /* ---------- The code ---------- */

  .tile {
    position: relative;
    grid-row: 1 / -1;
    grid-column: 2;
    align-self: center;
    aspect-ratio: 1;
    inline-size: 100%;
    border-radius: 2.6rem;
    /* Four modules of quiet zone (a 29-module code fills about 26rem of the 34rem tile). */
    padding: 3.8rem;
    background: var(--starlight);
    color: var(--abyss);
    box-shadow:
      0 0 0 0.9rem color-mix(in oklch, var(--starlight) 7%, transparent),
      0 2rem 8rem -1rem color-mix(in oklch, oklch(0.62 0.1 285) 55%, transparent);
  }
  .code {
    inline-size: 100%;
    block-size: 100%;
    fill: currentColor;
  }
  .tile.glass {
    background: color-mix(in oklch, var(--starlight) 5%, transparent);
    box-shadow: inset 0 0 0 0.12rem color-mix(in oklch, var(--starlight) 16%, transparent);
    color: var(--mist);
  }
  .placeholder {
    block-size: 100%;
    display: grid;
    place-content: center;
    justify-items: center;
    gap: 2rem;
    text-align: center;
  }
  .placeholder svg {
    inline-size: 7rem;
    fill: none;
    stroke: currentColor;
    stroke-width: 1.6;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
  .placeholder p {
    font-size: 1.5rem;
    font-weight: 500;
    max-inline-size: 16ch;
    text-wrap: balance;
  }

  /* ---------- Steps: three stars on one line ---------- */

  .steps {
    grid-column: 1;
    position: relative;
    margin-block-start: 4.5rem;
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    margin: 0;
    padding: 0;
    list-style: none;
  }
  /* The line the stars sit on, from the first star's center to the last. */
  .steps::before {
    content: "";
    position: absolute;
    inset-block-start: 0.6rem;
    inset-inline: 0.6rem calc(100% / 3 - 0.6rem);
    block-size: 0.12rem;
    background: color-mix(in oklch, var(--starlight) 18%, transparent);
  }
  .steps li {
    position: relative;
    display: grid;
    align-content: start;
    gap: 0.55rem;
    padding-inline-end: 3rem;
  }
  /* The lit part of the line: from a finished star to the next. */
  .steps li.done:not(:last-child)::after {
    content: "";
    position: absolute;
    inset-block-start: 0.6rem;
    inset-inline: 0.6rem -0.6rem;
    block-size: 0.12rem;
    background: color-mix(in oklch, var(--starlight) 70%, transparent);
  }
  .star {
    position: relative;
    z-index: 1;
    inline-size: 1.3rem;
    aspect-ratio: 1;
    border-radius: 50%;
    margin-block-end: 1.1rem;
    background: var(--abyss);
    box-shadow: inset 0 0 0 0.14rem color-mix(in oklch, var(--starlight) 35%, transparent);
  }
  .done .star {
    background: var(--starlight);
    box-shadow: none;
  }
  .current .star {
    background: var(--starlight);
    box-shadow:
      0 0 0 0.5rem color-mix(in oklch, var(--starlight) 14%, transparent),
      0 0 2.4rem 0.6rem color-mix(in oklch, oklch(0.8 0.08 285) 60%, transparent);
  }
  .label {
    display: flex;
    align-items: baseline;
    gap: 0.9rem;
    font-size: 1.6rem;
    font-weight: 600;
  }
  .n {
    font-size: 1.2rem;
    color: var(--faint);
    font-variant-numeric: tabular-nums;
  }
  .pending .label {
    color: var(--faint);
  }
  .detail {
    font-size: 1.3rem;
    color: var(--mist);
  }

  @media (prefers-reduced-motion: no-preference) {
    .current .star::after {
      content: "";
      position: absolute;
      inset: -0.5rem;
      border-radius: 50%;
      box-shadow: 0 0 0 0.12rem color-mix(in oklch, var(--starlight) 40%, transparent);
      animation: halo 2.8s var(--ease-out) infinite;
    }
  }
  @keyframes halo {
    from {
      scale: 1;
      opacity: 1;
    }
    to {
      scale: 2.4;
      opacity: 0;
    }
  }
</style>
