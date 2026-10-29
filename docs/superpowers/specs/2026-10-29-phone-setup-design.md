# Phone setup (and a Crunchyroll quality plan)

Status: approved direction, 29 Oct 2026. Extends `2026-10-28-vela-design.md`; its constraints
(4K, copy rules, secrets never in the repo or image, daemon API on 127.0.0.1) still hold.

## Why

Installing currently needs a second USB stick carrying hand-written `vela.toml`, `secrets.json`
and `authorized_keys`, because the flashed installer stick is read-only and a public installer
can't hold anyone's keys. That's the least "easy to set up" part of Vela. Instead, the box
installs with no settings and asks for them on first boot, through the owner's phone.

## The flow

1. Install from the one stick. The drive is wiped; no settings are copied (a VELA stick still
   works as an optional override, unchanged).
2. First boot: no `/etc/vela/vela.toml`, so Vela starts in **setup mode**. The TV shows a setup
   screen: a QR code, the address it encodes (`http://<box ip>:7301/`), and a 6-digit PIN.
3. The phone scans the QR code (the PIN rides in the URL fragment, so scanning is enough; typing
   the address asks for the PIN). The page is a short mobile wizard:
   1. **Media.** Jellyfin servers found on the network (Jellyfin's UDP discovery, port 7359) or a
      typed address; then username and password. The box calls `POST /Users/AuthenticateByName`
      and keeps only the returned access token and user ID (`jellyfinToken`, `jellyfinUserId` in
      `secrets.json`). The password is never stored or logged. "Skip" keeps the mock library.
   2. **SSH access.** A GitHub username (the box fetches `https://github.com/<name>.keys`) or a
      pasted public key. The phone shows each key's type and SHA256 fingerprint to confirm.
      Required, with an explicit "skip, I understand I can't change settings later" escape.
   3. **Done.** A summary; "Finish" commits.
4. The TV follows along live (step reached, server name, keys added) and switches to the normal
   home screen when setup commits. The setup listener shuts down.

TVs and Home Assistant stay as they are (install.md step 9) in this phase. A later PR adds an
optional wizard step: paste a Home Assistant token, pick TVs from HA's `media_player` entities.

`vela-ctl setup` (root, over SSH) reopens setup mode, for a new Jellyfin server or keys.

## Design

### Setup mode is a root-owned fact

- **Never configured** = `/etc/vela/vela.toml` does not exist. `vela-setup-gate.service` (root,
  oneshot, before greetd, `ConditionPathExists=!/etc/vela/vela.toml`) creates
  `/run/vela-setup/open` (directory `/run/vela-setup`, root 0755). `vela-ctl setup` creates the
  same file.
- **Broken config** = the file exists but doesn't parse: degraded mode, exactly as today. The two
  never overlap, so the VM's degraded-mode checks keep their meaning.
- The daemon (user `vela`) is in setup mode while `/run/vela-setup/open` exists. It can read but
  not create or remove it.

### Privilege boundary

The daemon runs as `vela`. `/etc/vela/vela.toml` and root's `authorized_keys`
(`/var/roothome/.ssh/authorized_keys`) are root's. So:

- The daemon writes what it may write itself: `secrets.json` (already `vela`-owned, 0600).
- Root's files are staged by the daemon in `/var/lib/vela/setup/` (`vela.toml`,
  `authorized_keys`) and then requested with `/var/lib/vela/setup/commit`.
- `vela-setup-commit.path` → `vela-setup-commit.service` (root) runs
  `/usr/libexec/vela/setup-commit`, which:
  - refuses to do anything unless `/run/vela-setup/open` exists (so a compromised `vela`
    session can't add root SSH keys after setup);
  - validates strictly: every `authorized_keys` line must pass `ssh-keygen -l -f`, at most 16
    keys, options prefixes rejected; `vela.toml` must pass the daemon's own schema
    (`node /usr/lib/vela/daemon/check-config.js <file>`, a new tiny entry point);
  - installs with root ownership and the right modes, runs `restorecon`, removes
    `/run/vela-setup/open` and the staging files, and restarts the vela daemon
    (`systemctl --user -M vela@ restart vela-daemon`);
  - logs every decision to the journal.

### The LAN listener

- A **separate** Fastify instance in the daemon, `0.0.0.0:7301`, started only in setup mode and
  closed on commit. Its own route table: the setup page's static files and `/setup/api/*`.
  The main API's routes are never registered on it; the main API stays on 127.0.0.1:7300.
- Every `/setup/api/*` call needs the PIN (`Authorization: Pin <6 digits>`). Five wrong PINs
  regenerate the PIN (the TV updates) and add a 30 s delay. PINs come from `crypto.randomInt`.
- Host check: requests must address the box's own IPs or its `.local` name; the Origin must
  match. POSTs need `Content-Type: application/json`. Bodies capped at 16 KB.
- The image has no firewall (firewalld isn't installed), so nothing to open or close.
- Plain HTTP on the home network. The Jellyfin password crosses the LAN once, in clear, to the
  box (and from the box to Jellyfin, which is usually HTTP too). Accepted: self-signed HTTPS
  would put a scary warning on the phone at the most important moment. Stated in install.md.
- The daemon publishes setup state on its existing event stream for the TV: the PIN, the URL,
  and the current step.

### Phone page

- Plain HTML/CSS/JS in `apps/setup/` (Vite, no framework), served by the setup listener. Same
  palette and fonts as the launcher, bundled. Mobile first, sentence-case copy.
- No third-party requests from the page.

### TV setup screen

- A launcher view shown while setup mode is on: wordmark, "Set up Vela with your phone", the QR
  code, the address, the PIN, and a live step list. Focus/remote input isn't needed on it.
- The QR code is rendered as **SVG** (crisp at 4K) by a bundled, MIT-licensed encoder
  (`qrcode-generator` or similar, chosen and pinned in the plan).
- If the box has no IP address yet: "Connect Ethernet to continue" with the link state.

### Installer

- No stick is the normal path. The kickstart's loud "no authorized_keys" warning becomes a
  quiet note ("setup on first boot will ask for SSH keys"). The VELA stick still works and, if it
  provides `vela.toml`, setup mode never triggers.

### Jellyfin with a user access token

The existing client sends `Authorization: MediaBrowser ..., Token="<token>"`; a user access token
from `AuthenticateByName` goes in the same field. The UserData write-back uses `jellyfinUserId`,
which `AuthenticateByName` returns. The CI Jellyfin integration test proves this end to end
(the seeded user `vela`/`vela` authenticates through the setup API).

## Testing

- Unit: PIN generation, lockout and regeneration; host/origin guard; route isolation (no main API
  route answers on the setup listener); GitHub key fetch and parsing (mocked); setup-commit
  validation cases in bats (bad key line, option prefix, too many keys, gate closed, bad TOML).
- Integration (CI Jellyfin): setup API → `AuthenticateByName` → `secrets.json` → resume rows load.
- VM smoke: the test image boots **without** `vela.toml` first. The diag service (root) reads the
  PIN, the runner drives the setup API from the host through `hostfwd` 7301 (skip media, add a
  test SSH key), and checks: the setup screen screenshot, `authorized_keys` installed root-owned
  0600, `vela.toml` installed, `/run/vela-setup/open` gone, port 7301 closed, home screen up. The
  existing checks (crash recovery, degraded mode) then run against the committed config.

## Out of scope here

- The Home Assistant/TV wizard step (next PR).
- Wi-Fi credentials entry (Ethernet is required for install anyway).

---

## Crunchyroll picture quality (plan, ships later as an update)

**What's possible.** Crunchyroll streams at most 1080p on every device, including its own apps
and Apple TV; there is no 4K Crunchyroll stream to get. So the goals are: (1) always get the
1080p stream, never 720p, and (2) make 1080p look as good as possible on a 4K TV.

**Step 1: measure (in `playback-check`, before any fix).** It's not known whether Chromium on
Linux, which only has Widevine L3, gets 1080p or 720p from Crunchyroll; public reports disagree.
Add a Crunchyroll step to `playback-check`, like the YouTube one: play a title, read the player's
quality menu and Chromium's `chrome://media-internals` (resolution, decoder, Widevine level), and
ask the owner to confirm. This decides which of the next steps are needed.

**Step 2, if Linux gets 1080p:** only quality-on-4K work (step 3).

**Step 2, if Linux is capped at 720p:** the cap is the service's DRM policy, so no setting on the
box lifts it. The fix is to play Crunchyroll on a certified (Widevine L1) device: a Google TV
Streamer or Chromecast with Google TV on another HDMI input. Vela's Crunchyroll tile then switches
the TV's input to that device through Home Assistant (both `samsungtv` and `androidtv_remote`
support source selection), launches Crunchyroll on it through `androidtv_remote`, and Home switches
the input back. The remote keeps working: arrows/OK/Back go to the streamer through Home
Assistant's `androidtv_remote` commands while it has the screen.

**Step 3: sharper 1080p on a 4K panel.** Chromium scales video with a basic filter, and protected
video can't be run through custom upscalers. Two options, compared by eye on the owner's TVs:
- switch the HDMI output to 1080p60 while Crunchyroll is in front and back to 4K on Home, so the
  TV's own upscaler (usually better, especially Samsung's) does the work; or
- leave 4K output and enable Chromium's higher-quality video scaling flags.
Whichever looks better becomes the default, per app manifest (`outputMode`).
