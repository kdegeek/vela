/** Status-bar clock; `h24` comes from the daemon's ui.clock24h. */
export function formatClock(d: Date, h24: boolean, locale?: string): string {
  return new Intl.DateTimeFormat(locale, { hour: "numeric", minute: "2-digit", hourCycle: h24 ? "h23" : "h12" }).format(d);
}
