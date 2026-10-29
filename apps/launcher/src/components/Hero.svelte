<script lang="ts">
  import type { HeroItem } from "@vela/shared";
  import { preload } from "../lib/art.js";
  import { createCrossfadeQueue } from "../lib/crossfade.js";

  let { item, sourceName, sourceColor }: { item: HeroItem | null; sourceName: string; sourceColor: string } = $props();

  // Two stacked layers: the incoming backdrop fades in over the outgoing one (700 ms). Only one
  // fade runs at a time; requests during a fade wait, and the latest one wins.
  type Layer = { url: string; ratio: string };
  let layers = $state<[Layer | null, Layer | null]>([null, null]);
  let front = $state(0);
  let layerEls = $state<HTMLElement[]>([]);
  let wanted: string | null = null;
  const ratios = new Map<string, string>();
  const reduced = typeof matchMedia === "function" ? matchMedia("(prefers-reduced-motion: reduce)") : null;
  const FADE_MS = 700;
  let fadeTimer: ReturnType<typeof setTimeout> | undefined;

  const fades = createCrossfadeQueue((url) => {
    const back = 1 - front;
    layers[back] = { url, ratio: ratios.get(url) ?? "16 / 9" };
    front = back;
    // transitionend ends the fade; the timer covers reduced motion (no transition) and a missed event.
    clearTimeout(fadeTimer);
    fadeTimer = setTimeout(endFade, reduced?.matches ? FADE_MS : FADE_MS + 200);
  });

  function endFade() {
    clearTimeout(fadeTimer);
    fades.done();
  }

  function onTransitionEnd(e: TransitionEvent, i: number) {
    if (e.propertyName === "opacity" && i === front && e.target === layerEls[i]) endFade();
  }

  $effect(() => {
    const url = item?.backdropUrl;
    if (!url || url === wanted) return;
    wanted = url;
    void preload(url).then((img) => {
      // A later focus may have asked for a different backdrop while this one decoded.
      if (!img || wanted !== url) return;
      ratios.set(url, `${img.naturalWidth} / ${img.naturalHeight}`);
      fades.request(url);
    });
  });

  $effect(() => () => clearTimeout(fadeTimer));

  let text = $state<HTMLElement>();
  $effect(() => {
    void item;
    if (!text || reduced?.matches) return;
    text.animate([{ opacity: 0, translate: "0 0.6rem" }, { opacity: 1, translate: "0 0" }], {
      duration: 420,
      easing: "cubic-bezier(0.2, 0.7, 0.2, 1)",
    });
  });

  const source = $derived(
    item?.label ?? (item?.kicker ? `${item.kicker} on ${sourceName}` : `On ${sourceName}`),
  );
  const meta = $derived(
    item ? [item.year, item.runtimeMin ? `${item.runtimeMin} min` : undefined].filter((v) => v !== undefined) : [],
  );
</script>

<div class="backdrop" aria-hidden="true">
  {#each layers as layer, i (i)}
    <div
      bind:this={layerEls[i]}
      ontransitionend={(e) => onTransitionEnd(e, i)}
      class="layer"
      class:is-front={front === i && !!layer}
      style:aspect-ratio={layer?.ratio}
      style:background-image={layer ? `url("${layer.url}")` : undefined}
    ></div>
  {/each}
</div>
<div class="scrim" aria-hidden="true"></div>

{#if item}
  <section class="hero" aria-live="polite" bind:this={text}>
    <p class="source"><span class="dot" style:background={sourceColor}></span>{source}</p>
    <h1 class="title">{item.title}</h1>
    {#if meta.length || item.tags.length}
      <p class="meta">
        {#each meta as m (m)}<span class="fact">{m}</span>{/each}
        {#each item.tags as t (t)}<span class="tag">{t}</span>{/each}
      </p>
    {/if}
    {#if item.blurb}
      <p class="blurb">{item.blurb}</p>
    {/if}
    {#if item.progress != null}
      <div class="progress">
        <div class="bar"><span style:inline-size="{Math.round(item.progress * 100)}%"></span></div>
        {#if item.remainingMin != null}<span>{item.remainingMin} min left</span>{/if}
      </div>
    {/if}
  </section>
{/if}

<style>
  .backdrop {
    position: absolute;
    inset: 0;
  }
  /*
   * Width-fit, top-anchored, sized to the image's own aspect ratio: a 3840 px backdrop lands
   * 1:1 on a 4K panel even when it is cinemascope (cover would upscale it ~1.34x and soften it).
   * The image's own bottom edge fades into the abyss, so it never shows as a hard line.
   */
  .layer {
    position: absolute;
    inset-block-start: 0;
    inset-inline: 0;
    min-block-size: 0;
    background-repeat: no-repeat;
    background-size: cover;
    background-position: 50% 0;
    mask-image: linear-gradient(to bottom, black 58%, transparent);
    opacity: 0;
  }
  .layer.is-front {
    opacity: 1;
  }

  .scrim {
    position: absolute;
    inset: 0;
    background:
      linear-gradient(to bottom, color-mix(in oklch, var(--abyss) 70%, transparent), transparent 22%),
      radial-gradient(ellipse 70% 60% at 8% 100%, color-mix(in oklch, var(--tint) 26%, transparent), transparent 70%),
      linear-gradient(to top, var(--abyss) 8%, color-mix(in oklch, var(--abyss) 88%, transparent) 36%, transparent 68%),
      linear-gradient(
        to right,
        color-mix(in oklch, var(--abyss) 92%, transparent) 0%,
        color-mix(in oklch, var(--abyss) 55%, transparent) 38%,
        transparent 68%
      );
  }

  @media (prefers-reduced-motion: no-preference) {
    .layer {
      transition: opacity 700ms var(--ease-out);
    }
    .backdrop {
      animation: drift 48s ease-in-out infinite alternate;
    }
    /* Lite effects: the backdrop holds still; the crossfade stays. */
    :global(:root[data-effects="lite"]) .backdrop {
      animation: none;
    }
  }
  @keyframes drift {
    from {
      scale: 1.03;
      translate: 0 0;
    }
    to {
      scale: 1.09;
      translate: -1.2% -0.8%;
    }
  }

  .hero {
    position: absolute;
    inset-block-start: 11rem;
    inset-inline-start: var(--gutter);
    max-inline-size: 46rem;
    display: grid;
    gap: 1.1rem;
  }
  .source {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    font-size: 1.1rem;
    font-weight: 600;
    color: var(--mist);
  }
  .dot {
    inline-size: 0.6rem;
    aspect-ratio: 1;
    border-radius: 50%;
  }
  .title {
    font-family: var(--display);
    font-weight: 400;
    font-size: 6.75rem;
    line-height: 0.92;
    letter-spacing: -0.015em;
    text-wrap: balance;
    text-shadow: 0 0.2rem 2.5rem color-mix(in oklch, var(--abyss) 60%, transparent);
  }
  .meta {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.5rem 1.25rem;
    font-size: 1.1rem;
    font-weight: 600;
    color: var(--mist);
  }
  .tag {
    padding-block: 0.15rem;
    padding-inline: 0.6rem;
    border: 0.0625rem solid var(--faint);
    border-radius: 0.35rem;
    font-size: 0.95rem;
  }
  .blurb {
    max-inline-size: 34rem;
    font-size: 1.35rem;
    line-height: 1.5;
    color: var(--mist);
    text-wrap: pretty;
    display: -webkit-box;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    overflow: clip;
  }
  .progress {
    display: flex;
    align-items: center;
    gap: 1rem;
    margin-block-start: 0.4rem;
    font-size: 1rem;
    font-weight: 600;
    color: var(--mist);
  }
  .bar {
    inline-size: 14rem;
    block-size: 0.3rem;
    border-radius: 99rem;
    background: var(--faint);
    overflow: clip;
  }
  .bar span {
    display: block;
    block-size: 100%;
    background: var(--tint);
  }
</style>
