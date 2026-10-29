# Phone setup implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or
> superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Install from one stick; on first boot the TV shows a QR code and PIN, the owner's phone
supplies Jellyfin sign-in and SSH keys, and the box commits them and goes to the home screen.

**Spec:** `docs/superpowers/specs/2026-10-29-phone-setup-design.md` (read it first; its
"Design" section is binding). The POC plan's Global Constraints
(`docs/superpowers/plans/2026-10-28-vela-poc.md`) still apply: 4K, bundled fonts, copy rules,
no secrets in repo or image, main API on 127.0.0.1:7300 only.

**Branch:** `feat/phone-setup`. One PR at the end. Commit per task with
`Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Task 1: Daemon setup core

Files: `packages/shared/src/api.ts`, `apps/daemon/src/setup/{state,pin,listener,jellyfinAuth,discovery,githubKeys,stage}.ts`
(+ tests), `apps/daemon/src/check-config.ts` (built to `check-config.js`), `apps/daemon/src/main.ts`,
`apps/daemon/src/server.ts` (setup state in `/api/home` and the event stream).

- [ ] Setup mode = `/run/vela-setup/open` exists (path overridable by env for tests). Watch it;
      start/stop the setup listener as it appears/disappears.
- [ ] PIN: 6 digits from `crypto.randomInt`; 5 wrong attempts → new PIN, 30 s delay; constant-time compare.
- [ ] Listener: separate Fastify instance on `0.0.0.0:7301` (env-overridable), own guard
      (Host = box IPs / `<hostname>.local` / `localhost` for tests; Origin match; JSON POSTs; 16 KB
      body limit), serves the built `apps/setup` files and `/setup/api/*` only. Test that no main
      API route answers on it.
- [ ] API: `GET /setup/api/state`; `GET /setup/api/jellyfin/discover` (UDP 7359 broadcast
      "who is JellyfinServer?", 2 s); `POST /setup/api/jellyfin` {url, username, password} →
      `AuthenticateByName` (device "Vela", client "Vela") → write `jellyfinToken`/`jellyfinUserId`
      to `secrets.json` via the existing atomic writer, stage `[media] provider = "jellyfin"` + url;
      `POST /setup/api/jellyfin/skip`; `POST /setup/api/ssh/github` {username} → fetch
      `https://github.com/<user>.keys` (validate username `^[A-Za-z0-9-]{1,39}$`), return keys with
      type + SHA256 fingerprint; `POST /setup/api/ssh/keys` {keys[]} stage; `POST /setup/api/ssh/skip`;
      `POST /setup/api/finish` → write staged `vela.toml` + `authorized_keys` to
      `/var/lib/vela/setup/`, then touch `commit`.
- [ ] Never log or store the password. Errors say what happened and what to do.
- [ ] `/api/home` gains `setup?: {pin, url, step, jellyfinServer?, keyCount}`; same over the event stream.
- [ ] `check-config.ts`: exits 0 if the file passes the config schema, else prints the problem, exits 1.
- [ ] Integration test (CI Jellyfin, seeded user `vela`/`vela`): drive `POST /setup/api/jellyfin`,
      then resume rows load with the stored token.

## Task 2: Root helper and OS wiring

Files: `os/files/usr/libexec/vela/setup-commit`, `os/files/usr/lib/systemd/system/{vela-setup-gate.service,vela-setup-commit.path,vela-setup-commit.service}`,
`os/files/usr/lib/tmpfiles.d/vela.conf`, `bin/vela-ctl` (`setup` subcommand), `os/build/finalize.sh`,
`os/test/setup-commit.bats`, `.github/workflows/test.yml`.

- [ ] Gate service: before greetd, creates `/run/vela-setup/open` when `/etc/vela/vela.toml` is missing.
- [ ] `setup-commit` per spec: gate check, `ssh-keygen -l` per key, reject option prefixes, ≤16 keys,
      `check-config.js` for vela.toml, install root-owned (authorized_keys 0600 in 0700 `.ssh`,
      vela.toml 0644), restorecon, remove gate + staging, restart the vela daemon. Paths
      env-overridable for bats. Bats cases: success, gate closed, bad key, option prefix, too many, bad TOML.
- [ ] `vela-ctl setup`: root only; creates the gate and restarts the daemon.
- [ ] finalize.sh enables and checks the units.

## Task 3: Phone page and TV setup screen

Files: `apps/setup/` (Vite, vanilla TS), `apps/launcher/src/components/SetupScreen.svelte`, `App.svelte`,
QR encoder dependency (MIT, pinned), `os/Containerfile` (ship `apps/setup/dist` to `/usr/lib/vela/setup`).

- [ ] Phone wizard: media (discovered servers + manual), SSH (GitHub username or paste, fingerprints
      to confirm, explicit skip), done. PIN from URL fragment or typed. Launcher palette/fonts bundled.
- [ ] TV screen: SVG QR (crisp at 4K), URL, PIN, live steps; "Connect Ethernet to continue" when no IP.
- [ ] Screenshots at 3840x2160 into `apps/launcher/screenshots/` (setup screen) and a 390 px phone
      screenshot of each wizard step into `apps/setup/screenshots/`.

## Task 4: Installer, VM smoke, docs

Files: `os/installer/kickstart.ks`, `dev/vm/test-image/**`, `dev/vm/run-smoke.sh`, `docs/install.md`,
`docs/acceptance.md`, `scripts/acceptance/hw-check.sh`.

- [ ] Kickstart: missing `authorized_keys` is a quiet note, not a warning.
- [ ] VM: boot without `vela.toml`; diag reads the PIN; runner drives the setup API via hostfwd 7301
      (skip media, add a test key); checks: setup screen screenshot, files installed with the right
      owner/mode, gate gone, 7301 closed, home up. Then the existing phases run. Update the CHECK count.
- [ ] install.md: one stick; setup with the phone; HTTP-on-LAN note; VELA stick as optional
      override; renumber and fix every step reference (acceptance.md, kickstart, example config, hw-check).
