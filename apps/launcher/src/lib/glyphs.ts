/**
 * App glyphs are single-color SVGs drawn with currentColor, so they are inlined (an <img> can't
 * take the brand color). They come from the daemon; scripts, foreign content and event
 * attributes are stripped anyway.
 */
const cache = new Map<string, Promise<string>>();

function sanitize(text: string): string {
  const doc = new DOMParser().parseFromString(text, "image/svg+xml");
  const svg = doc.documentElement;
  if (svg.nodeName !== "svg" || doc.querySelector("parsererror")) return "";
  for (const el of [svg, ...svg.querySelectorAll("*")]) {
    if (/^(script|foreignObject|iframe|use|image)$/i.test(el.nodeName)) {
      el.remove();
      continue;
    }
    for (const attr of [...el.attributes]) {
      if (/^on/i.test(attr.name) || /^\s*javascript:/i.test(attr.value) || /href$/i.test(attr.name)) el.removeAttribute(attr.name);
    }
  }
  svg.setAttribute("aria-hidden", "true");
  svg.setAttribute("focusable", "false");
  return new XMLSerializer().serializeToString(svg);
}

export function loadGlyph(url: string): Promise<string> {
  let p = cache.get(url);
  if (!p) {
    p = fetch(url)
      .then((r) => (r.ok ? r.text() : ""))
      .then(sanitize)
      .catch(() => "");
    cache.set(url, p);
  }
  return p;
}
