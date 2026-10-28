<script lang="ts">
  import { onMount, tick } from "svelte";
  import type { AppTile, HeroItem, HomeState, MediaCard, VelaEvent } from "@vela/shared";
  import { connect, type ActionResult } from "./lib/api.js";
  import { move, trackShift, type FocusState } from "./lib/focus.js";
  import { createHeroSettler } from "./lib/hero.js";
  import { NEUTRAL_TINT } from "./lib/tint.js";
  import { preload, sampleTint } from "./lib/art.js";
  import { actionForKey, pollGamepads, type Action } from "./lib/input.js";
  import TopBar from "./components/TopBar.svelte";
  import Hero from "./components/Hero.svelte";
  import Row from "./components/Row.svelte";
  import Tile from "./components/Tile.svelte";
  import Toast from "./components/Toast.svelte";

  type RowModel =
    | { id: "apps"; kind: "app"; title: string; items: AppTile[] }
    | { id: string; kind: "wide" | "poster"; title: string; items: MediaCard[] };

  let home = $state<HomeState | null>(null);
  let focus = $state<FocusState>({ row: 0, cols: [] });
  let shifts = $state<number[]>([]);
  let rowsOffset = $state(0);
  let hero = $state<HeroItem | null>(null);
  let tints = $state<Record<string, string>>({});
  let toast = $state<string | null>(null);
  let rowsEl = $state<HTMLElement>();
  let lefts: number[][] = [];

  const rows = $derived.by((): RowModel[] => {
    if (!home) return [];
    const out: RowModel[] = [];
    if (home.apps.length) out.push({ id: "apps", kind: "app", title: "Apps", items: home.apps });
    for (const r of home.rows) if (r.items.length) out.push({ id: r.id, kind: r.layout, title: r.title, items: r.items });
    return out;
  });

  /** Before the first hero paints, the wash glows nebula violet rather than gray. */
  const STARTUP_TINT = "oklch(0.62 0.1 285)";

  const tintFor = (item: HeroItem | null | undefined): string =>
    item?.tint ?? (item ? tints[item.backdropUrl] : undefined) ?? NEUTRAL_TINT;

  function heroAt(r: number, c: number): HeroItem | null {
    const row = rows[r];
    if (!row) return null;
    if (row.kind === "app") {
      const app = row.items[c];
      if (!app) return null;
      // An app without a feature keeps the current backdrop and names itself.
      return app.feature ?? { title: app.name, tags: [], backdropUrl: hero?.backdropUrl ?? "", sourceId: app.id };
    }
    return row.items[c] ?? null;
  }

  const source = $derived.by(() => {
    const id = hero?.sourceId ?? "";
    const app = home?.apps.find((a) => a.id === id);
    return { name: app?.name ?? id.charAt(0).toUpperCase() + id.slice(1), color: app?.brandColor ?? "var(--mist)" };
  });

  // ---------- Hero ----------

  // Neighbors are warmed only once focus rests, never per key repeat (each is a 4K decode).
  const settler = createHeroSettler<HeroItem>((item) => {
    paintHero(item);
    warmNeighbors();
  });

  function paintHero(item: HeroItem) {
    hero = item;
    const url = item.backdropUrl;
    if (!item.tint && url && !tints[url]) {
      void sampleTint(url).then((t) => {
        if (t) tints[url] = t;
      });
    }
  }

  /** Warms the decoder for the neighbors of the focused tile so the crossfade starts on time. */
  function warmNeighbors() {
    const r = focus.row;
    const c = focus.cols[r] ?? 0;
    for (const [rr, cc] of [[r, c + 1], [r, c - 1], [r + 1, focus.cols[r + 1] ?? 0], [r - 1, focus.cols[r - 1] ?? 0]] as const) {
      const url = heroAt(rr, cc)?.backdropUrl;
      if (url) void preload(url);
    }
  }

  // ---------- Layout and focus ----------

  function rem(): number {
    return parseFloat(getComputedStyle(document.documentElement).fontSize);
  }

  /** Measures untransformed tile edges, pins each row's focused tile to the start edge, and slides past rows away. */
  async function relayout() {
    await tick();
    if (!rowsEl) return;
    const rowEls = [...rowsEl.querySelectorAll<HTMLElement>("[data-row]")];
    const gutter = 6 * rem();
    lefts = rowEls.map((el) => [...el.querySelectorAll<HTMLElement>("[data-tile]")].map((t) => t.offsetLeft));
    shifts = rowEls.map((el, i) => {
      const track = el.querySelector<HTMLElement>("[data-track]");
      const left = lefts[i]?.[focus.cols[i] ?? 0] ?? 0;
      return trackShift(left, track?.offsetWidth ?? 0, el.clientWidth - 2 * gutter);
    });
    rowsOffset = rowEls.slice(0, focus.row).reduce((h, el) => h + el.offsetHeight, 0);
    const tile = rowEls[focus.row]?.querySelectorAll<HTMLElement>("[data-tile]")[focus.cols[focus.row] ?? 0];
    tile?.focus({ preventScroll: true });
  }

  function focusChanged() {
    void relayout();
    const item = heroAt(focus.row, focus.cols[focus.row] ?? 0);
    if (item) settler.focus(item);
  }

  function setHome(s: HomeState) {
    const first = home === null;
    home = s;
    // Keep the user's place when the daemon pushes fresh state; clamp to the new row lengths.
    const count = rows.length;
    const cols = rows.map((r, i) => Math.min(Math.max(0, focus.cols[i] ?? 0), Math.max(0, r.items.length - 1)));
    focus = { row: Math.min(focus.row, Math.max(0, count - 1)), cols };
    if (first) {
      const item = heroAt(focus.row, cols[focus.row] ?? 0);
      if (item) paintHero(item);
    }
    focusChanged();
  }

  // ---------- Actions ----------

  let toastTimer: ReturnType<typeof setTimeout> | undefined;
  function showToast(message: string) {
    // The daemon also pushes toasts over the socket; the same message twice shows once.
    toast = message;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => (toast = null), 5000);
  }

  const UNREACHABLE = "Vela's service isn't answering. It restarts on its own, so try again in a moment.";

  function openHomePanel() {
    // TODO(Task 10): open the Home panel (devices and scenes) from the Home tile.
  }

  function launchFailed(app: AppTile, r: ActionResult) {
    if (r.ok) return;
    if (r.reason === "installing") showToast(`${app.name} is still installing`);
    else if (r.reason === "missing") showToast(`${app.name} isn't installed yet. It will install automatically when the box is online.`);
    else showToast(`${app.name} didn't open. Check its manifest in /etc/vela/apps.`);
  }

  function playFailed(card: MediaCard, r: ActionResult) {
    if (r.ok || r.reason === "mock") return; // the daemon explains mock titles itself
    if (r.reason.includes("not configured")) showToast("Jellyfin isn't set up. Add it to /etc/vela/vela.toml.");
    else showToast(`${card.title} didn't start. Try again in a moment.`);
  }

  async function activate() {
    const row = rows[focus.row];
    const c = focus.cols[focus.row] ?? 0;
    if (!row) return;
    try {
      if (row.kind === "app") {
        const app = row.items[c];
        if (!app) return;
        if (app.id === "home") return openHomePanel();
        launchFailed(app, await api.launch(app.id));
      } else {
        const card = row.items[c];
        if (card) playFailed(card, await api.play(card.key));
      }
    } catch {
      showToast(UNREACHABLE);
    }
  }

  function handle(a: Action) {
    if (!home || rows.length === 0) return;
    if (a.kind === "move") {
      const next = move(focus, a.dir, { rowLefts: lefts }, shifts);
      if (next === focus) return;
      focus = next;
      focusChanged();
    } else if (a.kind === "activate") {
      void activate();
    } else if (a.kind === "back") {
      if (focus.row === 0) return;
      focus = { ...focus, row: 0 };
      focusChanged();
    }
    // "home": already home; nothing to do.
  }

  function onEvent(e: VelaEvent) {
    if (e.type === "toast") showToast(e.message);
    else if (e.type === "home-state-changed") void api.home().then(setHome);
    else if (e.type === "app-state" && home) {
      const app = home.apps.find((a) => a.id === e.id);
      if (app) app.status = e.status;
    } else if (e.type === "home") void relayout();
  }

  const api = connect(location.origin, onEvent);

  onMount(() => {
    void api.home().then(setHome);
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Tab") return e.preventDefault();
      const a = actionForKey(e.key);
      if (!a) return;
      e.preventDefault();
      handle(a);
    };
    const onResize = () => void relayout();
    addEventListener("keydown", onKey);
    addEventListener("resize", onResize);
    const stopPad = pollGamepads(handle);
    return () => {
      removeEventListener("keydown", onKey);
      removeEventListener("resize", onResize);
      stopPad();
      settler.dispose();
      api.close();
    };
  });

  function pick(r: number, c: number) {
    focus = { row: r, cols: focus.cols.map((v, i) => (i === r ? c : v)) };
    focusChanged();
    void activate();
  }
</script>

<div class="stage" style:--tint={hero ? tintFor(hero) : STARTUP_TINT}>
  <Hero item={hero} sourceName={source.name} sourceColor={source.color} />
  <TopBar status={home?.status ?? null} />

  <main class="rows" bind:this={rowsEl} style:translate="0 {-rowsOffset}px">
    {#each rows as row, r (row.id)}
      <Row title={row.title} active={r === focus.row} past={r < focus.row} shift={shifts[r] ?? 0}>
        {#if row.kind === "app"}
          {#each row.items as app, c (app.id)}
            <Tile kind="app" {app} tint={tintFor(app.feature)} onpick={() => pick(r, c)} />
          {/each}
        {:else}
          {#each row.items as card, c (card.key)}
            <Tile kind={row.kind} {card} tint={tintFor(card)} onpick={() => pick(r, c)} />
          {/each}
        {/if}
      </Row>
    {/each}
  </main>

  <Toast message={toast} />
</div>

<style>
  .stage {
    position: fixed;
    inset: 0;
    overflow: clip;
    background: var(--abyss);
  }
  .rows {
    position: absolute;
    inset-block-start: 38.5rem;
    inset-inline: 0;
  }
  @media (prefers-reduced-motion: no-preference) {
    .stage {
      transition: --tint 900ms var(--ease-out);
    }
    .rows {
      transition: translate 480ms var(--ease-out);
    }
  }
</style>
