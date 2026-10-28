/**
 * At most one backdrop crossfade in flight; the last request wins. While a fade runs, requests
 * only update `pending`; when the fade ends (done), the latest pending URL fades in next.
 * Pure: the caller swaps layers in `swap` and calls `done` on transitionend (or a timer).
 */
export function createCrossfadeQueue(swap: (url: string) => void) {
  let current: string | null = null;
  let pending: string | null = null;
  let fading = false;

  const start = (url: string) => {
    current = url;
    fading = true;
    swap(url);
  };

  return {
    request(url: string) {
      if (fading) pending = url;
      else if (url !== current) start(url);
    },
    done() {
      if (!fading) return;
      fading = false;
      const next = pending;
      pending = null;
      if (next !== null && next !== current) start(next);
    },
    get fading() {
      return fading;
    },
  };
}
