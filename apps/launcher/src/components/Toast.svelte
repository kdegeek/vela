<script lang="ts">
  /** "sheet": the Home panel is open, so the toast sits at the foot of the sheet instead of over its title. */
  let { message, placement = "top" }: { message: string | null; placement?: "top" | "sheet" } = $props();
</script>

{#if message}
  <div class="toast" class:in-sheet={placement === "sheet"} role="status">
    <span class="dot"></span>
    <p>{message}</p>
  </div>
{/if}

<style>
  .toast {
    position: absolute;
    inset-block-start: 7.5rem;
    inset-inline-end: var(--gutter);
    z-index: 9;
    display: flex;
    align-items: center;
    gap: 0.9rem;
    max-inline-size: 60rem;
    padding-block: 1rem;
    padding-inline: 1.3rem 1.6rem;
    border-radius: 1rem;
    border: 0.0625rem solid color-mix(in oklch, var(--starlight) 12%, transparent);
    background: color-mix(in oklch, var(--abyss) 62%, transparent);
    backdrop-filter: blur(1.6rem) saturate(1.3);
    box-shadow: 0 1.5rem 3rem -1rem var(--shadow);
    font-size: 1.2rem;
    font-weight: 600;
    line-height: 1.4;
  }
  :global(:root[data-effects="lite"]) .toast {
    backdrop-filter: none;
    background: color-mix(in oklch, var(--abyss) 90%, var(--tint) 6%);
  }
  .toast.in-sheet {
    inset-block: auto 7rem;
    inset-inline-end: 4.5rem;
    max-inline-size: 41rem;
  }
  .dot {
    flex: none;
    inline-size: 0.6rem;
    aspect-ratio: 1;
    border-radius: 50%;
    background: var(--tint);
    box-shadow: 0 0 1rem var(--tint);
  }
  @media (prefers-reduced-motion: no-preference) {
    .toast {
      animation: rise 360ms var(--ease-out) both;
    }
  }
  @keyframes rise {
    from {
      opacity: 0;
      translate: 0 -0.8rem;
    }
  }
</style>
