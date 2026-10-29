Screenshots: the phone page at 390 px wide (3x device pixels, headless Chromium as a mobile device) against the built daemon in mock mode, the same run as the launcher's `13-setup.jpg` (see `apps/launcher/screenshots/README.md`): `VELA_SETUP_DIR=apps/setup/dist`, a temporary gate file, staging dir and secrets file. Long pages are shot whole by growing the viewport to the page, so the fixed sky covers them as it does while scrolling.

Every request goes to the real setup API except where noted:

- `1-pin.jpg`: the address was typed (no `#pin=` fragment), so the page asks for the PIN.
- `2-media.jpg`, `3-media-signin.jpg`: Jellyfin servers on the network, then one chosen: the full server address sits above the username and password. Discovery found nothing on the build machine's network, so `/setup/api/jellyfin/discover` is answered with two example servers.
- `4-media-error.jpg`: sign-in refused. With no Jellyfin server to refuse it, the `POST /setup/api/jellyfin` answer is the daemon's own 422 text, injected.
- `5-ssh.jpg`: the SSH step after skipping media (real).
- `6-ssh-error.jpg`: a real refusal: GitHub user `octocat` has no key that passes Vela's checks.
- `7-ssh-keys.jpg`: keys found on GitHub, with type, fingerprint and comment to check. The GitHub lookup is answered with two throwaway keys (fingerprints from `ssh-keygen -l`); adding them goes through the real API, which validates and stages them.
- `8-done.jpg`: the summary before Finish (real).
- `9-committing.jpg`: after Finish (real; the files are staged).
- `10-stuck.jpg`: nothing installed the staged files for 20 seconds (no root helper on the build machine), so the page says the box most likely refused them.
- `11-finished.jpg`: the gate file was removed by hand, as `setup-commit` does; the listener closed and the page reads that as done.
- `12-locked.jpg`: five wrong PINs from this phone (a separate run): the daemon's lockout, with the countdown.
