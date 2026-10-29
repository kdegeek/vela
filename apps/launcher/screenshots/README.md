Screenshots: 3840x2160 headless Chromium against the daemon in mock mode; app statuses are overridden to `ready` (Steam to `installing` in 4-toast) because macOS has no flatpak, so the daemon reports every app as `missing`.

Task 10 set (same setup; `/api/launch` and `/api/play` are also stubbed to 200 so the launch screen shows on macOS, and the config has one Home Assistant TV and one IR soundbar with no Home Assistant or transmitter present):

- `5-home-panel.jpg`: the Home panel (glass sheet) opened from the Home tile, focus on the first button.
- `6-home-panel-error.jpg`: a TV command failing; the toast is the daemon's own copy and sits at the foot of the sheet.
- `7-launch-mid.jpg`, `9-play-mid.jpg`: the launch screen mid-expansion from the chosen tile. A 4K screenshot takes longer than the 520 ms expansion, so these were taken with animations slowed 20x through the DevTools protocol.
- `8-launch.jpg`, `9-play.jpg`: the settled launch screen for an app ("Opening Plex") and for playback ("Resuming Sintel").
- `10-lite-home.jpg`, `11-lite-panel.jpg`, `12-lite-launch.jpg`: `ui.effects = "lite"`: solid tiles and sheet, no blur, no drift.
