/**
 * Delays applying the hero until focus rests, so scrubbing across tiles doesn't strobe the
 * backdrop. Only the last item of a burst is applied.
 */
export function createHeroSettler<T>(apply: (item: T) => void, delayMs = 120): { focus(item: T): void; dispose(): void } {
  let timer: ReturnType<typeof setTimeout> | undefined;
  return {
    focus(item: T) {
      clearTimeout(timer);
      timer = setTimeout(() => {
        timer = undefined;
        apply(item);
      }, delayMs);
    },
    dispose() {
      clearTimeout(timer);
      timer = undefined;
    },
  };
}
