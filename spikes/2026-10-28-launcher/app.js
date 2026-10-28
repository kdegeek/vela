// Vela launcher spike. Throwaway: proves the look and the remote-driven focus model.

const art = (f) => `assets/art/${f}`;

const SOURCES = {
  plex:        { name: "Plex",        color: "oklch(0.80 0.16 80)" },
  youtube:     { name: "YouTube",     color: "oklch(0.63 0.24 28)" },
  jellyfin:    { name: "Jellyfin",    color: "oklch(0.66 0.17 300)" },
  crunchyroll: { name: "Crunchyroll", color: "oklch(0.72 0.19 50)" },
  steam:       { name: "Steam",       color: "oklch(0.70 0.10 240)" },
  home:        { name: "Home",         color: "oklch(0.75 0.13 220)" },
};

const TITLES = {
  sintel:  { title: "Sintel", year: 2010, runtime: "15 min", tags: ["4K", "Fantasy"], tint: "oklch(0.84 0.12 80)",
             backdrop: art("bd-sintel2.jpg"), poster: art("p-sintel.jpg"),
             blurb: "A lone girl crosses frozen wilds to find the baby dragon she once raised." },
  tos:     { title: "Tears of Steel", year: 2012, runtime: "12 min", tags: ["4K", "Sci-fi"], tint: "oklch(0.82 0.08 235)",
             backdrop: art("bd-tos.jpg"), poster: art("p-tos.jpg"),
             blurb: "In a future Amsterdam, a band of scientists stage a last stand against the robots they let loose." },
  cosmos:  { title: "Cosmos Laundromat", year: 2015, runtime: "12 min", tags: ["Drama"], tint: "oklch(0.86 0.08 170)",
             backdrop: art("bd-cosmos.jpg"), poster: art("p-cosmos.jpg"),
             blurb: "On a desolate island, a weary sheep named Franck meets a salesman who offers him the gift of a lifetime." },
  spring:  { title: "Spring", year: 2019, runtime: "8 min", tags: ["Animation"], tint: "oklch(0.78 0.15 45)",
             backdrop: art("bd-spring.jpg"), poster: null,
             blurb: "A shepherd girl and her dog face ancient spirits to keep the cycle of life turning." },
  charge:  { title: "Charge", year: 2022, runtime: "4 min", tags: ["Action"], tint: "oklch(0.72 0.17 265)",
             backdrop: art("bd-charge.jpg"), poster: art("p-charge.jpg"),
             blurb: "In a dystopian future, an old man defends his last energy source from a relentless robot." },
  sprite:  { title: "Sprite Fright", year: 2021, runtime: "10 min", tags: ["Comedy", "Horror"], tint: "oklch(0.76 0.14 305)",
             backdrop: art("bd-sprite.jpg"), poster: art("p-sprite.jpg"),
             blurb: "A group of teens on a forest getaway run into some very territorial mushroom sprites." },
  bbb:     { title: "Big Buck Bunny", year: 2008, runtime: "10 min", tags: ["4K", "Comedy"], tint: "oklch(0.84 0.13 110)",
             backdrop: art("bd-bbb.jpg"), poster: art("p-bbb.jpg"),
             blurb: "A gentle giant of a rabbit gets even with the three rodents who made his morning miserable." },
  ed:      { title: "Elephants Dream", year: 2006, runtime: "11 min", tags: ["Surreal"], tint: "oklch(0.76 0.12 60)",
             backdrop: art("bd-ed.jpg"), poster: art("p-ed.jpg"),
             blurb: "Two men wander an endless machine that only one of them believes in." },
};

// Each app tile previews something from inside that app in the hero.
const APPS = [
  { id: "plex",        feature: { ...TITLES.tos, kicker: "On deck" } },
  { id: "youtube",     feature: { ...TITLES.sprite, title: "Making Sprite Fright", runtime: "24 min", tags: ["Blender Studio"],
                                  blurb: "How a small studio built a forest full of mushrooms, entirely in open-source software.", kicker: "Recommended" } },
  { id: "jellyfin",    feature: { ...TITLES.sintel, kicker: "Continue watching" } },
  { id: "crunchyroll", feature: { ...TITLES.spring, kicker: "New episode" } },
  { id: "steam",       feature: { title: "Big Picture", year: null, runtime: null, tags: ["14 games installed"], tint: "oklch(0.80 0.08 240)",
                                  backdrop: art("bd-tos2.jpg"), blurb: "Pick up where you left off. Two updates finished downloading overnight.", kicker: "Your library" } },
  { id: "home",        feature: { title: "Good evening", year: null, runtime: null, tags: ["Front door locked", "Lights at 40%"], tint: "oklch(0.86 0.07 230)",
                                  backdrop: art("bd-cam.jpg"), blurb: "Everything at home is quiet. Movie scene will dim the living room and close the blinds.", label: "Your home" } },
];

const CONTINUE = [
  { ...TITLES.sintel, source: "jellyfin", progress: 0.41, left: "9 min left" },
  { ...TITLES.tos, source: "plex", progress: 0.72, left: "3 min left" },
  { ...TITLES.cosmos, source: "plex", progress: 0.18, left: "10 min left" },
  { ...TITLES.charge, source: "jellyfin", progress: 0.55, left: "2 min left" },
  { ...TITLES.spring, source: "crunchyroll", progress: 0.3, left: "6 min left" },
];

const RECENT = ["sintel", "bbb", "tos", "cosmos", "sprite", "charge"].map((k) => ({ ...TITLES[k], source: "plex" }));

const GLYPHS = {
  plex: `<svg viewBox="0 0 40 40"><path d="M14 6h8l10 14-10 14h-8l10-14z" fill="currentColor"/></svg>`,
  youtube: `<svg viewBox="0 0 40 40"><rect x="3" y="9" width="34" height="22" rx="7" fill="currentColor"/><path d="M17 14.5v11l9-5.5z" fill="oklch(1 0 none)"/></svg>`,
  jellyfin: `<svg viewBox="0 0 40 40"><path d="M20 5c-3 0-14 19-12.5 22S32 30 33 27 23 5 20 5zm0 9c1.6 0 6.5 10 5.8 11.5S15 27 14.3 25.5 18.4 14 20 14z" fill="currentColor"/></svg>`,
  crunchyroll: `<svg viewBox="0 0 40 40"><circle cx="20" cy="20" r="15" fill="currentColor"/><circle cx="24" cy="17" r="7" fill="oklch(0.2 0.03 265)"/><circle cx="26" cy="15" r="2.6" fill="currentColor"/></svg>`,
  steam: `<svg viewBox="0 0 40 40"><circle cx="20" cy="20" r="15" fill="none" stroke="currentColor" stroke-width="3"/><circle cx="25" cy="16" r="5" fill="none" stroke="currentColor" stroke-width="2.6"/><circle cx="14" cy="25" r="3.4" fill="currentColor"/><path d="M14 25l9-7" stroke="currentColor" stroke-width="2.6"/></svg>`,
  home: `<svg viewBox="0 0 40 40"><path d="M6 19 20 7l14 12M10 16v16h20V16" fill="none" stroke="currentColor" stroke-width="3" stroke-linejoin="round"/><circle cx="20" cy="23" r="3" fill="currentColor"/></svg>`,
};

// ---------- Build rows ----------

const rowsEl = document.querySelector(".rows");
const rows = [];

function makeRow(label, kind, items, render) {
  const row = document.createElement("section");
  row.className = `row row-${kind}`;
  row.innerHTML = `<h2 class="row-label">${label}</h2><div class="track"></div>`;
  const track = row.querySelector(".track");
  const tiles = items.map((item) => {
    const b = document.createElement("button");
    b.className = `tile tile-${kind}`;
    b.style.setProperty("--tint", item.tint ?? item.feature?.tint);
    b.innerHTML = render(item);
    track.append(b);
    return b;
  });
  rowsEl.append(row);
  rows.push({ el: row, track, tiles, items, kind, col: 0 });
}

makeRow("Apps", "app", APPS, (a) => {
  const s = SOURCES[a.id];
  return `<span class="app-glyph" style="color:${s.color}">${GLYPHS[a.id]}</span><span class="app-name">${s.name}</span>`;
});

makeRow("Continue watching", "wide", CONTINUE, (t) => `
  <img src="${t.backdrop}" alt="" loading="lazy">
  <span class="wide-info">
    <span class="wide-title">${t.title}</span>
    <span class="wide-left">${t.left}</span>
  </span>
  <span class="progress"><span style="inline-size:${t.progress * 100}%"></span></span>`);

makeRow("Recently added to Plex", "poster", RECENT, (t) => `<img src="${t.poster}" alt="${t.title}" loading="lazy">`);

// ---------- Hero ----------

const hero = {
  layers: [...document.querySelectorAll(".backdrop-layer")],
  front: 0,
  current: null,
  title: document.querySelector(".hero-title"),
  meta: document.querySelector(".hero-meta"),
  blurb: document.querySelector(".hero-blurb"),
  source: document.querySelector(".source-name"),
  sourceDot: document.querySelector(".source-dot"),
  progress: document.querySelector(".hero-progress"),
};

const stage = document.querySelector(".stage");
const preloaded = new Map();
function preload(src) {
  if (!preloaded.has(src)) {
    const img = new Image();
    img.src = src;
    preloaded.set(src, img.decode().catch(() => {}));
  }
  return preloaded.get(src);
}
[...APPS.map((a) => a.feature), ...CONTINUE, ...RECENT].forEach((t) => preload(t.backdrop));

let heroTimer;
function showHero(item, sourceId) {
  clearTimeout(heroTimer);
  // Small delay so fast scrubbing across tiles doesn't strobe the backdrop.
  heroTimer = setTimeout(() => paintHero(item, sourceId), 120);
}

async function paintHero(item, sourceId) {
  const s = SOURCES[sourceId];
  stage.style.setProperty("--tint", item.tint);
  hero.source.textContent = item.label ?? (item.kicker ? `${item.kicker} on ${s.name}` : `On ${s.name}`);
  hero.sourceDot.style.background = s.color;
  hero.title.textContent = item.title;
  hero.meta.replaceChildren(...[item.year, item.runtime, ...(item.tags ?? [])].filter(Boolean).map((t) => {
    const span = document.createElement("span");
    span.textContent = t;
    return span;
  }));
  hero.blurb.textContent = item.blurb ?? "";
  hero.progress.hidden = item.progress == null;
  if (item.progress != null) {
    hero.progress.querySelector(".bar span").style.inlineSize = `${item.progress * 100}%`;
    hero.progress.querySelector(".left").textContent = item.left;
  }
  document.querySelector(".hero").animate(
    [{ opacity: 0, translate: "0 0.6rem" }, { opacity: 1, translate: "0 0" }],
    { duration: matchMedia("(prefers-reduced-motion: reduce)").matches ? 0 : 420, easing: "cubic-bezier(.2,.7,.2,1)" });

  if (hero.current === item.backdrop) return;
  hero.current = item.backdrop;
  await preload(item.backdrop);
  if (hero.current !== item.backdrop) return;
  const next = hero.layers[1 - hero.front];
  next.style.backgroundImage = `url("${item.backdrop}")`;
  next.classList.add("is-front");
  hero.layers[hero.front].classList.remove("is-front");
  hero.front = 1 - hero.front;
}

// ---------- Spatial focus ----------

let rowIndex = 0;

function heroFor(r, col) {
  const item = r.items[col];
  if (r.kind === "app") return [item.feature, item.id];
  return [item, item.source];
}

function focusCurrent() {
  const r = rows[rowIndex];
  const tile = r.tiles[r.col];
  tile.focus({ preventScroll: true });

  // Keep the focused tile pinned to the row's start edge, Apple TV style.
  const rem = parseFloat(getComputedStyle(document.documentElement).fontSize);
  const max = Math.max(0, r.track.offsetWidth - (r.el.clientWidth - 12 * rem));
  r.track.style.translate = `${-Math.min(tile.offsetLeft, max)}px 0`;

  // Rows above the focused one slide up out of the way.
  const offset = rows.slice(0, rowIndex).reduce((h, x) => h + x.el.offsetHeight, 0);
  rowsEl.style.translate = `0 ${-offset}px`;
  rows.forEach((x, i) => x.el.classList.toggle("is-past", i < rowIndex));
  rows.forEach((x, i) => x.el.classList.toggle("is-active", i === rowIndex));

  showHero(...heroFor(r, r.col));
}

function move(dx, dy) {
  if (launchOpen()) return;
  if (dy) {
    const next = Math.min(rows.length - 1, Math.max(0, rowIndex + dy));
    if (next === rowIndex) return;
    // Keep roughly the same horizontal position when changing rows.
    const from = rows[rowIndex].tiles[rows[rowIndex].col].getBoundingClientRect();
    rowIndex = next;
    const r = rows[rowIndex];
    let best = 0, bestDist = Infinity;
    r.tiles.forEach((t, i) => {
      const d = Math.abs(t.getBoundingClientRect().left - from.left);
      if (d < bestDist) { bestDist = d; best = i; }
    });
    r.col = best;
  } else {
    const r = rows[rowIndex];
    r.col = Math.min(r.tiles.length - 1, Math.max(0, r.col + dx));
  }
  focusCurrent();
}

// ---------- Launch overlay ----------

const launch = document.querySelector(".launch");
const launchOpen = () => !launch.hidden;

function activate() {
  if (launchOpen()) return;
  const r = rows[rowIndex];
  const item = r.items[r.col];
  const [feature, sourceId] = heroFor(r, r.col);
  const s = SOURCES[sourceId];
  launch.style.setProperty("--brand", s.color);
  launch.querySelector(".launch-mark").innerHTML = GLYPHS[sourceId];
  launch.querySelector(".launch-title").textContent =
    r.kind === "app" ? `Opening ${s.name}` : item.progress != null
      ? `Resuming ${feature.title} in ${s.name}` : `Playing ${feature.title} in ${s.name}`;
  const rect = r.tiles[r.col].getBoundingClientRect();
  launch.style.setProperty("--ox", `${rect.left + rect.width / 2}px`);
  launch.style.setProperty("--oy", `${rect.top + rect.height / 2}px`);
  launch.hidden = false;
}

function back() {
  if (launchOpen()) {
    launch.hidden = true;
    focusCurrent();
  } else if (rowIndex > 0) {
    rowIndex = 0;
    focusCurrent();
  }
}

// ---------- Input: keyboard, remote (as keyboard/CEC), gamepad ----------

const KEYS = {
  ArrowLeft: () => move(-1, 0), ArrowRight: () => move(1, 0),
  ArrowUp: () => move(0, -1), ArrowDown: () => move(0, 1),
  Enter: activate, " ": activate,
  Escape: back, Backspace: back, BrowserBack: back, GoBack: back,
};
addEventListener("keydown", (e) => {
  const fn = KEYS[e.key];
  if (fn) { e.preventDefault(); fn(); }
});
// Mouse/touch is a fallback, not the primary input.
rows.forEach((r, ri) => r.tiles.forEach((t, ci) => t.addEventListener("click", () => {
  rowIndex = ri; r.col = ci; focusCurrent(); activate();
})));

const pad = { held: {}, next: {} };
function pollGamepad(now) {
  for (const gp of navigator.getGamepads?.() ?? []) {
    if (!gp) continue;
    const [ax, ay] = gp.axes;
    const dirs = {
      left: gp.buttons[14]?.pressed || ax < -0.5, right: gp.buttons[15]?.pressed || ax > 0.5,
      up: gp.buttons[12]?.pressed || ay < -0.5, down: gp.buttons[13]?.pressed || ay > 0.5,
      a: gp.buttons[0]?.pressed, b: gp.buttons[1]?.pressed,
    };
    const act = { left: () => move(-1, 0), right: () => move(1, 0), up: () => move(0, -1), down: () => move(0, 1), a: activate, b: back };
    for (const [k, on] of Object.entries(dirs)) {
      if (!on) { pad.held[k] = false; continue; }
      const repeatable = !["a", "b"].includes(k);
      if (!pad.held[k]) { act[k](); pad.held[k] = true; pad.next[k] = now + 380; }
      else if (repeatable && now >= pad.next[k]) { act[k](); pad.next[k] = now + 110; }
    }
  }
  requestAnimationFrame(pollGamepad);
}
requestAnimationFrame(pollGamepad);

// ---------- Clock ----------

const clock = document.querySelector(".clock");
const fmt = new Intl.DateTimeFormat(undefined, { hour: "numeric", minute: "2-digit" });
const tick = () => { clock.textContent = fmt.format(new Date()); };
tick();
setInterval(tick, 10_000);

// ---------- Start ----------

rows[0].col = 2; // Jellyfin: "continue watching Sintel" is a good first impression.
paintHero(...heroFor(rows[0], 2));
focusCurrent();
