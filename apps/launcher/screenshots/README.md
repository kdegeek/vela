Screenshots: 3840x2160 headless Chromium against the daemon in mock mode; app statuses are overridden to `ready` (Steam to `installing` in 4-toast) because macOS has no flatpak, so the daemon reports every app as `missing`.

Task 10 set (same setup; `/api/launch` and `/api/play` are also stubbed to 200 so the launch screen shows on macOS, and the config has one Home Assistant TV and one IR soundbar with no Home Assistant or transmitter present):

- `5-home-panel.jpg`: the Home panel (glass sheet) opened from the Home tile, focus on the first button.
- `6-home-panel-error.jpg`: a TV command failing; the toast is the daemon's own copy and sits at the foot of the sheet.
- `7-launch-mid.jpg`, `9-play-mid.jpg`: the launch screen mid-expansion from the chosen tile. A 4K screenshot takes longer than the 520 ms expansion, so these were taken with animations slowed 20x through the DevTools protocol.
- `8-launch.jpg`, `9-play.jpg`: the settled launch screen for an app ("Opening Plex") and for playback ("Resuming Sintel").
- `10-lite-home.jpg`, `11-lite-panel.jpg`, `12-lite-launch.jpg`: `ui.effects = "lite"`: solid tiles and sheet, no blur, no drift.

Phone setup set (Task 3 of the phone-setup plan). The built daemon runs in mock mode with no `vela.toml`, a temporary gate file (`VELA_SETUP_GATE`), temporary staging and secrets paths, and `VELA_SETUP_DIR=apps/setup/dist`. The setup address in `/api/home` and in the event stream's `setup` events is rewritten to `http://192.168.1.42:7301/` so the screenshots don't carry the build machine's address; the PIN is the daemon's own. The QR code in `13-setup.jpg` and `14-setup-progress.jpg` was decoded from the JPEG with jsQR and reads `http://192.168.1.42:7301/#pin=<the PIN shown>`.

- `13-setup.jpg`: the setup screen on first boot: QR code, address, PIN, and the media step current.
- `14-setup-progress.jpg`: after the phone answered both steps (media skipped and two SSH keys added through the real setup API); the Jellyfin server name "Living room" is injected into the status, since there was no Jellyfin server to sign in to.
- `15-setup-no-network.jpg`: the box has no IPv4 address (`url: null`, injected): "Connect Ethernet to continue".
- `16-setup-timed-out.jpg`: no gate, no `vela.toml`, and root's `expired` marker next to the gate: the daemon reports `setup: {expired: true}` (not injected).
