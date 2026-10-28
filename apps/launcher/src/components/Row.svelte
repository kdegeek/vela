<script lang="ts">
  import type { Snippet } from "svelte";

  let {
    title,
    active,
    past,
    shift,
    children,
  }: { title: string; active: boolean; past: boolean; shift: number; children: Snippet } = $props();
</script>

<section class="row" class:is-active={active} class:is-past={past} data-row>
  <h2 class="label">{title}</h2>
  <div class="track" data-track style:translate="{shift}px 0">
    {@render children()}
  </div>
</section>

<style>
  .row {
    padding-inline: var(--gutter);
    padding-block-end: 2.25rem;
    opacity: 0.55;
  }
  .row.is-active {
    opacity: 1;
  }
  .row.is-past {
    opacity: 0;
  }
  .label {
    margin-block-end: 1rem;
    font-size: 1.15rem;
    font-weight: 600;
    color: var(--mist);
  }
  .track {
    position: relative;
    display: flex;
    gap: 1.5rem;
    inline-size: max-content;
  }
  @media (prefers-reduced-motion: no-preference) {
    .track {
      transition: translate 480ms var(--ease-out);
    }
    .row {
      transition: opacity 360ms var(--ease-out);
    }
  }
</style>
