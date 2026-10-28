<script lang="ts">
  import type { AppTile, MediaCard } from "@vela/shared";
  import { cardArt } from "../lib/art.js";

  type Props = { tint: string; onpick: () => void } & (
    | { kind: "app"; app: AppTile }
    | { kind: "wide" | "poster"; card: MediaCard }
  );
  let props: Props = $props();

  const STATUS: Record<string, string> = { installing: "Installing", missing: "Not installed", running: "Open" };
</script>

{#if props.kind === "app"}
  {@const app = props.app}
  <button
    class="tile app"
    class:is-unavailable={app.status === "installing" || app.status === "missing"}
    style:--tint={props.tint}
    data-tile
    onclick={props.onpick}
  >
    <!-- Glyphs are masks, so no SVG markup enters the DOM: the silhouette takes the brand color
         and any white detail (YouTube's play triangle) is drawn back through a luminance mask. -->
    <span class="glyph" style:--glyph={`url("${app.glyphUrl}")`} style:--brand={app.brandColor} aria-hidden="true">
      <span class="glyph-light"></span>
    </span>
    <span class="name">{app.name}</span>
    {#if STATUS[app.status]}<span class="state" class:is-running={app.status === "running"}>{STATUS[app.status]}</span>{/if}
  </button>
{:else if props.kind === "wide"}
  {@const card = props.card}
  <button class="tile wide" style:--tint={props.tint} data-tile onclick={props.onpick}>
    <img src={cardArt(card.backdropUrl)} alt="" decoding="async" />
    <span class="info">
      <span class="wide-title">{card.title}</span>
      {#if card.remainingMin != null}<span class="left">{card.remainingMin} min left</span>{/if}
    </span>
    {#if card.progress != null}
      <span class="progress"><span style:inline-size="{Math.round(card.progress * 100)}%"></span></span>
    {/if}
  </button>
{:else}
  {@const card = props.card}
  <button class="tile poster" style:--tint={props.tint} data-tile onclick={props.onpick}>
    {#if card.posterUrl}
      <img src={card.posterUrl} alt={card.title} decoding="async" />
    {:else}
      <img class="fallback-art" src={cardArt(card.backdropUrl)} alt="" decoding="async" />
      <span class="poster-title">{card.title}</span>
    {/if}
  </button>
{/if}

<style>
  .tile {
    position: relative;
    flex: none;
    border-radius: 0.9rem;
    overflow: clip;
    background: color-mix(in oklch, var(--nebula) 55%, transparent);
    box-shadow: 0 1rem 2rem -1rem var(--shadow);
    text-align: start;
  }
  .tile:focus-visible {
    outline: 0.22rem solid var(--starlight);
    outline-offset: 0.35rem;
    scale: 1.07;
    box-shadow:
      0 0 4rem 0.5rem color-mix(in oklch, var(--tint) 50%, transparent),
      0 1.5rem 3rem -1rem oklch(0 0 none / 0.7);
    z-index: 1;
  }
  .tile:active {
    scale: 1.02;
  }
  @media (prefers-reduced-motion: no-preference) {
    .tile {
      transition:
        scale 260ms var(--ease-out),
        box-shadow 360ms var(--ease-out);
    }
  }

  /* Apps: frosted glass over the backdrop. */
  .app {
    inline-size: 14rem;
    block-size: 8.25rem;
    display: grid;
    place-content: center;
    justify-items: center;
    gap: 0.6rem;
    background: color-mix(in oklch, var(--abyss) 50%, transparent);
    backdrop-filter: blur(1.2rem) saturate(1.4);
    border: 0.0625rem solid color-mix(in oklch, var(--starlight) 10%, transparent);
  }
  .glyph,
  .glyph-light {
    display: block;
    inline-size: 3rem;
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
  .name {
    font-size: 1.05rem;
    font-weight: 650;
  }
  .state {
    position: absolute;
    inset-block-start: 0.7rem;
    inset-inline-end: 0.8rem;
    font-size: 0.8rem;
    font-weight: 600;
    color: var(--faint);
  }
  .state.is-running {
    color: var(--mist);
  }
  .is-unavailable .glyph {
    opacity: 0.45;
  }

  /* Continue watching */
  .wide {
    inline-size: 22rem;
    aspect-ratio: 16 / 9;
  }
  .wide img,
  .poster img {
    inline-size: 100%;
    block-size: 100%;
    object-fit: cover;
  }
  .wide::after {
    content: "";
    position: absolute;
    inset: 0;
    background: linear-gradient(to top, oklch(0 0 none / 0.8), transparent 55%);
  }
  .info {
    position: absolute;
    inset-inline: 1.1rem;
    inset-block-end: 1.15rem;
    z-index: 1;
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 1rem;
  }
  .wide-title {
    font-family: var(--display);
    font-size: 1.75rem;
    line-height: 1;
    overflow: clip;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .left {
    font-size: 0.9rem;
    font-weight: 600;
    color: var(--mist);
    white-space: nowrap;
  }
  .progress {
    position: absolute;
    inset-inline: 0;
    inset-block-end: 0;
    z-index: 1;
    block-size: 0.3rem;
    background: oklch(1 0 none / 0.2);
  }
  .progress span {
    display: block;
    block-size: 100%;
    background: var(--tint);
  }

  /* Posters */
  .poster {
    inline-size: 10.5rem;
    aspect-ratio: 2 / 3;
  }
  .poster::after {
    content: "";
    position: absolute;
    inset: 0;
    border-radius: inherit;
    box-shadow: inset 0 0 0 0.0625rem color-mix(in oklch, var(--starlight) 8%, transparent);
  }
  .fallback-art {
    filter: brightness(0.7);
  }
  .poster-title {
    position: absolute;
    inset-inline: 0.9rem;
    inset-block-end: 1rem;
    font-family: var(--display);
    font-size: 1.6rem;
    line-height: 1.05;
  }
</style>
