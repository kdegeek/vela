<script lang="ts" module>
  export interface LaunchView {
    /** "Opening Plex", "Resuming Sintel". */
    line: string;
    /** Small line under the title, e.g. "42 min left". */
    detail?: string;
    backdropUrl: string;
    tint: string;
    glyphUrl?: string;
    brandColor?: string;
    /** The chosen tile's on-screen rect; the backdrop expands from it. */
    from: { top: number; left: number; width: number; height: number } | null;
  }
</script>

<script lang="ts">
  import { cssUrl } from "../lib/css.js";
  let { view, leaving, capMs }: { view: LaunchView; leaving: boolean; capMs: number } = $props();

  const EXPAND_MS = 520;
  const reduced = typeof matchMedia === "function" ? matchMedia("(prefers-reduced-motion: reduce)").matches : false;

  let frame = $state<HTMLElement>();
  let art = $state<HTMLElement>();
  let shade = $state<HTMLElement>();
  let fill = $state<HTMLElement>();

  // The backdrop opens out of the tile: a clip that grows from the tile's rect to the full screen
  // while the art settles from a slight zoom and darkens to 40 % brightness.
  $effect(() => {
    if (!frame || !art || !shade || reduced) return;
    const r = view.from;
    const easing = "cubic-bezier(0.2, 0.7, 0.2, 1)";
    if (r) {
      const right = innerWidth - r.left - r.width;
      const bottom = innerHeight - r.top - r.height;
      const radius = getComputedStyle(document.documentElement).fontSize;
      frame.animate(
        [
          { clipPath: `inset(${r.top}px ${right}px ${bottom}px ${r.left}px round calc(${radius} * 0.9))` },
          { clipPath: "inset(0 0 0 0 round 0)" },
        ],
        { duration: EXPAND_MS, easing },
      );
    }
    art.animate([{ scale: 1.12 }, { scale: 1 }], { duration: EXPAND_MS + 380, easing });
    shade.animate([{ opacity: 0 }, { opacity: 1 }], { duration: EXPAND_MS, easing });
  });

  // The line creeps toward 92 % across the wait so it never claims to be done before the app is.
  $effect(() => {
    if (!fill || reduced) return;
    const a = fill.animate([{ scale: "0 1" }, { scale: "0.92 1" }], { duration: capMs, easing: "cubic-bezier(0.3, 0.4, 0.4, 1)", fill: "forwards" });
    return () => a.cancel();
  });

  // Done: the line runs out to the end as the screen fades.
  $effect(() => {
    if (!leaving || !fill || reduced) return;
    fill.animate([{ scale: getComputedStyle(fill).scale }, { scale: "1 1" }], { duration: 240, easing: "cubic-bezier(0.2, 0.7, 0.2, 1)", fill: "forwards" });
  });
</script>

<div class="launch" class:is-leaving={leaving} style:--tint={view.tint} role="status" aria-live="polite">
  <div class="frame" bind:this={frame} aria-hidden="true">
    <div class="art" bind:this={art} style:background-image={`url("${view.backdropUrl}")`}></div>
    <div class="shade" bind:this={shade}></div>
  </div>

  <div class="content">
    {#if view.glyphUrl}
      <span class="glyph-wrap" aria-hidden="true">
        <span class="glyph" style:--glyph={cssUrl(view.glyphUrl)} style:--brand={view.brandColor ?? "var(--starlight)"}>
          <span class="glyph-light"></span>
        </span>
      </span>
    {/if}
    <p class="line">{view.line}</p>
    {#if view.detail}<p class="detail">{view.detail}</p>{/if}
  </div>

  <div class="track" aria-hidden="true"><span class="fill" bind:this={fill}></span></div>
</div>

<style>
  .launch {
    position: absolute;
    inset: 0;
    z-index: 8;
    overflow: clip;
  }
  .frame {
    position: absolute;
    inset: 0;
    background: var(--abyss);
  }
  .art {
    position: absolute;
    inset: 0;
    background-size: cover;
    background-position: 50% 30%;
  }
  /* 60 % abyss over the art leaves it at 40 %; the tint pools behind the glyph. */
  .shade {
    position: absolute;
    inset: 0;
    background:
      radial-gradient(ellipse 42% 46% at 50% 46%, color-mix(in oklch, var(--tint) 26%, transparent), transparent 75%),
      radial-gradient(ellipse 120% 90% at 50% 50%, color-mix(in oklch, var(--abyss) 60%, transparent), color-mix(in oklch, var(--abyss) 86%, transparent));
  }

  .content {
    position: absolute;
    inset: 0;
    display: grid;
    place-content: center;
    justify-items: center;
    gap: 2.6rem;
    text-align: center;
  }
  .glyph-wrap {
    display: grid;
    place-items: center;
    filter: drop-shadow(0 0 3.5rem color-mix(in oklch, var(--brand, var(--tint)) 45%, transparent));
  }
  .glyph,
  .glyph-light {
    display: block;
    inline-size: 9rem;
    aspect-ratio: 1;
    mask-image: var(--glyph);
    mask-size: contain;
    mask-repeat: no-repeat;
    mask-position: center;
  }
  .glyph {
    background: var(--brand);
  }
  .glyph-light {
    background: var(--starlight);
    mask-mode: luminance;
  }
  .line {
    font-family: var(--display);
    font-size: 4.5rem;
    line-height: 1;
    letter-spacing: -0.01em;
    text-wrap: balance;
    max-inline-size: 80rem;
    text-shadow: 0 0.2rem 2.5rem color-mix(in oklch, var(--abyss) 70%, transparent);
  }
  .detail {
    margin-block-start: -1.4rem;
    font-size: 1.3rem;
    font-weight: 600;
    color: var(--mist);
  }

  .track {
    position: absolute;
    inset-inline: 0;
    inset-block-end: 0;
    block-size: 0.3rem;
    background: color-mix(in oklch, var(--starlight) 10%, transparent);
  }
  .fill {
    display: block;
    block-size: 100%;
    background: var(--tint);
    box-shadow: 0 0 1.2rem var(--tint);
    transform-origin: 0 50%;
    scale: 0.92 1;
  }

  @media (prefers-reduced-motion: no-preference) {
    .content {
      animation: rise 480ms var(--ease-out) 200ms both;
    }
    /* The line arrives once the backdrop has filled the screen. */
    .track {
      animation: appear 300ms var(--ease-out) 420ms both;
    }
    .glyph-wrap {
      animation: breathe 2.4s var(--ease-out) 700ms infinite alternate;
    }
    .launch {
      transition: opacity 280ms var(--ease-out);
    }
  }
  .launch.is-leaving {
    opacity: 0;
  }
  @keyframes rise {
    from {
      opacity: 0;
      translate: 0 1.2rem;
    }
  }
  @keyframes appear {
    from {
      opacity: 0;
    }
  }
  @keyframes breathe {
    to {
      scale: 1.035;
    }
  }
</style>
