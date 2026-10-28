<script lang="ts">
  import type { HomeStatus } from "@vela/shared";

  let { status }: { status: HomeStatus | null } = $props();

  const fmt = new Intl.DateTimeFormat(undefined, { hour: "numeric", minute: "2-digit" });
  let now = $state(new Date());
  $effect(() => {
    const t = setInterval(() => (now = new Date()), 5_000);
    return () => clearInterval(t);
  });
</script>

<header class="topbar">
  <svg class="wordmark" viewBox="0 0 120 28" role="img" aria-label="Vela">
    <g class="sail" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
      <path d="M4 22 L12 6 L20 18 L14 24 Z" />
    </g>
    <g fill="currentColor">
      <circle cx="4" cy="22" r="1.8" /><circle cx="12" cy="6" r="2.2" />
      <circle cx="20" cy="18" r="1.6" /><circle cx="14" cy="24" r="1.4" />
    </g>
    <text x="30" y="20" class="wordmark-text" fill="currentColor">Vela</text>
  </svg>

  <div class="status">
    {#if status}
      <div class="hints" aria-hidden="true">
        <span><kbd>←</kbd><kbd>→</kbd><kbd>↑</kbd><kbd>↓</kbd> Move</span>
        <span><kbd>Enter</kbd> Open</span>
        <span><kbd>Esc</kbd> Back</span>
      </div>
    {/if}
    {#if status?.line}
      <p class="line" role="status"><span class="line-dot"></span>{status.line}</p>
    {/if}
    {#if status?.insideTemp}
      <span class="chip">
        <svg viewBox="0 0 16 16" aria-hidden="true">
          <path d="M2 8 8 2.5 14 8M4 7v6.5h8V7" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round" />
        </svg>
        {status.insideTemp} inside
      </span>
    {/if}
    <time class="clock" datetime={now.toISOString()}>{fmt.format(now)}</time>
  </div>
</header>

<style>
  .topbar {
    position: absolute;
    inset-block-start: 2.75rem;
    inset-inline: var(--gutter);
    z-index: 2;
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  .wordmark {
    block-size: 2.4rem;
    inline-size: auto;
    color: var(--starlight);
    overflow: visible;
  }
  .sail {
    opacity: 0.55;
  }
  .wordmark-text {
    font-family: var(--display);
    font-size: 22px;
    letter-spacing: 0.01em;
  }
  .status {
    display: flex;
    align-items: center;
    gap: 1.5rem;
    font-size: 1.15rem;
    font-weight: 500;
  }
  .hints {
    display: flex;
    margin-inline-end: 1rem;
    gap: 1.75rem;
    font-size: 0.9rem;
    font-weight: 600;
    color: var(--faint);
  }
  kbd {
    display: inline-block;
    min-inline-size: 1.5rem;
    margin-inline-end: 0.25rem;
    padding-inline: 0.35rem;
    border: 0.0625rem solid var(--faint);
    border-radius: 0.3rem;
    font-family: inherit;
    font-size: 0.8rem;
    line-height: 1.4rem;
    text-align: center;
  }
  .line {
    display: inline-flex;
    align-items: center;
    gap: 0.6rem;
    max-inline-size: 40rem;
    font-size: 1rem;
    font-weight: 600;
    color: var(--mist);
  }
  .line-dot {
    flex: none;
    inline-size: 0.55rem;
    aspect-ratio: 1;
    border-radius: 50%;
    background: oklch(0.8 0.14 70);
  }
  .chip {
    display: inline-flex;
    align-items: center;
    gap: 0.5rem;
    padding-block: 0.45rem;
    padding-inline: 0.8rem 0.95rem;
    border-radius: 99rem;
    background: color-mix(in oklch, var(--abyss) 45%, transparent);
    backdrop-filter: blur(0.8rem);
    color: var(--mist);
  }
  .chip svg {
    inline-size: 1rem;
  }
  .clock {
    font-size: 1.6rem;
    font-weight: 500;
    font-variant-numeric: tabular-nums;
  }
</style>
