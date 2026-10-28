import type { HeroItem, MediaCard, Row } from "@vela/shared";

export interface MockProvider {
  hero(slug: string): HeroItem;
  rows(): Row[];
}

interface MockTitle {
  title: string;
  year: number;
  runtimeMin: number;
  tags: string[];
  blurb: string;
  tint: string;
  backdrop: string;
  poster?: string;
  /** App the title pretends to belong to. */
  sourceId: string;
}

const art = (f: string): string => `/api/art/mock/${f}`;

// Facts are from the Blender Foundation / Blender Studio releases. Backdrops are true 4K frames (see assets/mock/CREDITS.md).
// Sintel has no poster of at least 1056 px on Commons, so it appears only in wide rows.
const TITLES: Record<string, MockTitle> = {
  sintel: {
    title: "Sintel",
    year: 2010,
    runtimeMin: 15,
    tags: ["4K", "Fantasy"],
    blurb: "A young woman befriends a baby dragon, then sets out to find him after he is taken.",
    tint: "oklch(0.84 0.12 80)",
    backdrop: art("bd-sintel-1.jpg"),
    sourceId: "jellyfin",
  },
  bbb: {
    title: "Big Buck Bunny",
    year: 2008,
    runtimeMin: 10,
    tags: ["4K", "Comedy"],
    blurb: "A gentle giant of a rabbit gets even with the three rodents who made his morning miserable.",
    tint: "oklch(0.84 0.13 110)",
    backdrop: art("bd-bbb-1.jpg"),
    poster: art("p-bbb.jpg"),
    sourceId: "plex",
  },
  tos: {
    title: "Tears of Steel",
    year: 2012,
    runtimeMin: 12,
    tags: ["4K", "Sci-fi"],
    blurb: "In a future Amsterdam, a band of scientists stage a last stand against the robots they let loose.",
    tint: "oklch(0.82 0.08 235)",
    backdrop: art("bd-tos-1.jpg"),
    poster: art("p-tos.jpg"),
    sourceId: "plex",
  },
  charge: {
    title: "Charge",
    year: 2022,
    runtimeMin: 4,
    tags: ["4K", "Sci-fi"],
    blurb: "A wounded man in a dystopian future fights to keep hold of his last energy cell.",
    tint: "oklch(0.72 0.17 265)",
    backdrop: art("bd-charge-1.jpg"),
    poster: art("p-charge.jpg"),
    sourceId: "jellyfin",
  },
};

function toHero(t: MockTitle, extra: Partial<HeroItem> = {}): HeroItem {
  return {
    title: t.title,
    year: t.year,
    runtimeMin: t.runtimeMin,
    tags: t.tags,
    blurb: t.blurb,
    backdropUrl: t.backdrop,
    tint: t.tint,
    sourceId: t.sourceId,
    ...extra,
  };
}

function card(slug: string, extra: Partial<MediaCard> = {}): MediaCard {
  const t = TITLES[slug]!;
  return { ...toHero(t), key: `mock:${slug}`, posterUrl: t.poster, ...extra };
}

export function createMockProvider(): MockProvider {
  const k = "Continue watching";
  return {
    hero: (slug) => toHero(TITLES[slug] ?? TITLES.tos!),
    rows: () => [
      {
        id: "continue",
        title: k,
        layout: "wide",
        items: [
          card("sintel", { kicker: k, progress: 0.41, remainingMin: 9 }),
          card("tos", { kicker: k, progress: 0.72, remainingMin: 4 }),
          card("bbb", { kicker: k, progress: 0.3, remainingMin: 7 }),
          card("charge", { kicker: k, progress: 0.55, remainingMin: 2 }),
        ],
      },
      { id: "recent", title: "Recently added", layout: "poster", items: [card("bbb"), card("tos"), card("charge")] },
    ],
  };
}
