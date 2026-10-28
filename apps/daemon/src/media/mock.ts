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
  poster: string;
  /** App the title pretends to belong to. */
  sourceId: string;
}

const art = (f: string): string => `/api/art/mock/${f}`;

// Facts are from the Blender Foundation / Blender Studio releases. Only films with a backdrop of at least 3840 px have art here.
const TITLES: Record<string, MockTitle> = {
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
    tags: ["Sci-fi", "Action"],
    blurb: "In a dystopian future, an old man defends his last energy source from a relentless robot.",
    tint: "oklch(0.72 0.17 265)",
    backdrop: art("bd-charge.jpg"),
    poster: art("p-charge.jpg"),
    sourceId: "jellyfin",
  },
};

/** Extra hero-only frames from Tears of Steel, so app tiles do not all show the same still. */
const TOS_FRAMES: Record<string, string> = {
  "tos-2": art("bd-tos-2.jpg"),
  "tos-3": art("bd-tos-3.jpg"),
  "tos-4": art("bd-tos-4.jpg"),
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
  return {
    hero(slug) {
      const base = slug.startsWith("tos-") ? "tos" : slug;
      const t = TITLES[base] ?? TITLES.tos!;
      return toHero(t, TOS_FRAMES[slug] ? { backdropUrl: TOS_FRAMES[slug] } : {});
    },
    rows() {
      return [
        {
          id: "continue",
          title: "Continue watching",
          layout: "wide",
          items: [
            card("charge", { kicker: "Continue watching", progress: 0.55, remainingMin: 2 }),
            card("tos", { kicker: "Continue watching", progress: 0.72, remainingMin: 3, sourceId: "plex" }),
          ],
        },
        {
          id: "recent",
          title: "Recently added",
          layout: "poster",
          items: [card("tos"), card("charge")],
        },
      ];
    },
  };
}
