# Vela: design

Status: draft for review, 2026-10-28
Scope of this document: the whole product direction, and in detail the **proof of concept (POC)**.

## 1. What Vela is, and why

Vela is an open-source operating system that turns an x86 Mac mini into a living-room box: a
streaming player, a light gaming console, and a smart-home hub, driven entirely from a remote.
It is a hobby project that leans heavily on existing open-source apps, datasets, and assets
instead of rebuilding them.

The owner's typical evening is scrolling YouTube or watching a movie on Plex. Vela has to make
that feel as good as an Apple TV, while also opening Jellyfin, Crunchyroll, and Steam Big
Picture, and controlling the TV and other devices.

Must-haves from the owner:

- **Easy to set up.**
- **Gorgeous.** It must look like a premium product, not a Linux desktop or a Kodi skin.
- **Extendable.** Adding an app or integration should not mean editing the core.
- **Remote control out of the box.**
- **4K.** The owner judged 1080p "far too soft". The interface renders at native 3840x2160, and
  video plays at 4K wherever the source allows.
- Supports Jellyfin, Plex, YouTube, Crunchyroll, Steam Big Picture, IR command and control, and
  network control of Samsung and Android TVs.

## 2. Decisions already made

These were chosen by the owner and are not open:

| Area | Decision |
|---|---|
| Hardware target | **2018 Mac mini** (Intel UHD 630, HDMI 2.0, Apple T2 chip). It is the only Intel Mac mini that outputs 4K at 60 Hz. A Raspberry Pi 5 was considered and rejected: it cannot hardware-decode VP9 (4K YouTube) and cannot run Steam natively. |
| Base OS | **Fedora Atomic, built as a bootc image.** Image-based, atomic updates with rollback. |
| UI technology | **Web technology** (HTML/CSS/TypeScript) shown full screen in a Chromium-based window. |
| Smart home | **Bundle Home Assistant** on the box. |
| Visual direction | **Cinematic hero**: full-bleed backdrop art for whatever has focus, with rows of tiles below. See the spike (§4). |
| 4K scope | Native 4K interface at 60 fps with 4K art; video at the best each source allows. DRM services are capped lower on Linux (see §7) and that is accepted. |
| POC scope | Boots into Vela, launches the real apps, shows real data, **plus TV and IR control**. |
| Remote hardware | A **Flirc remote and Flirc USB receiver** (IR in, appears to Linux as a USB keyboard). For sending IR: a **USB-C IR dongle** and a **headphone-jack-powered IR blaster**, both owned. A gamepad should also work. Long term, the owner plans to flash custom firmware onto an Apple Remote (out of scope here). |
| Name | Vela (the constellation; "the Sails"). |
| Install target | **Wipe macOS.** Update macOS one last time (to get the latest T2 firmware), then Vela takes the whole internal SSD. No dual-boot. |
| TVs | The POC controls **both** a Samsung TV and an Android/Google TV. |
| Hardware access | The builder **does not yet have access** to the Mac mini, the TVs, or the IR hardware. See §8.1. |

## 3. Goals, non-goals, anti-goals

### POC goals (what "done" means)

On a 2018 Mac mini connected to a 4K TV, with only the Flirc remote in hand:

1. **Boot to Vela.** Power on, and the Vela home screen appears with no desktop, login prompt,
   or terminal visible. The panel shows 3840x2160 at 60 Hz.
2. **Smooth at 4K.** Moving focus around the home screen holds 60 fps on the UHD 630 (measured,
   see §8).
3. **Launch real apps.** Each of the six app tiles opens the real thing: YouTube (TV interface),
   Plex, Jellyfin, Crunchyroll, Steam Big Picture, and Home. The remote's Home button always
   returns to the Vela home screen. Back works inside apps the way that app expects.
4. **Real media data.** The Continue Watching row shows the owner's real Jellyfin resume list
   with 4K art. Choosing a card resumes that item at the right position. Playback progress is
   reported back to Jellyfin so the list stays correct.
5. **4K playback.** A 4K HEVC file from Jellyfin and a 4K YouTube video play without dropped
   frames using hardware decode.
6. **TV control.** From the remote, Vela turns a Samsung TV and an Android/Google TV on and off
   and changes their volume over the network. Samsung TVs often can't be woken from standby over
   the network; if so, Samsung power-on falls back to IR, and that still counts.
7. **IR control.** Vela sends at least one IR command (for example power to a soundbar) through
   one of the owner's IR transmitters, using a code from an open IR database.
8. **Reproducible install.** A documented path from a blank 2018 Mac mini to a running Vela,
   that someone else could follow.

### Product goals beyond the POC (direction, not POC work)

- An on-TV first-run setup that needs nothing but the remote and a phone (QR codes).
- Plex rows with real data (On Deck, Recently Added) alongside Jellyfin.
- Third-party apps and integrations added by dropping in a manifest or plugin.
- HDR output, once Intel HDR on Linux matures.
- Custom Apple Remote firmware support.

### Non-goals for the POC

HDR; a phone remote app; voice control; Netflix, Disney+, or other services not listed;
multiple user profiles; an app store; over-the-air update UI (bootc's own update mechanism is
enough); Raspberry Pi or non-Mac hardware; older Mac minis (2012–2014, which cannot do 4K60).

### Anti-goals (would make it a failure even if it technically works)

- **It looks or behaves like desktop Linux** during normal use: a mouse cursor, window
  decorations, a desktop, a terminal, notification pop-ups, a GNOME/KDE dialog, or a visible
  boot log.
- **It stutters.** Focus movement or the backdrop crossfade drops frames at 4K.
- **It looks soft.** Anything rendered at 1080p and upscaled, including art requested at too
  low a resolution.
- **You get stuck.** Any state the remote cannot leave (an app that swallows the Home button, a
  dialog that needs a mouse, a crash that leaves a black screen).
- **It looks templated**, like a generic dark media-center theme.

## 4. Design language (from the spike)

A throwaway spike lives in `spikes/2026-10-28-launcher/`. Its screenshots are in
`spikes/2026-10-28-launcher/screenshots/`. The owner approved its look as a first pass. Carry
over the design language, not the code:

- **Layout.** Full-bleed backdrop art of the focused item. A scrim darkens the left and bottom.
  Top-left: the Vela wordmark. Top-right: a home-status chip and the clock. Left-aligned hero:
  source line ("Continue watching on Jellyfin"), the title, year/runtime/tags, a two-line
  synopsis, a progress bar when resumable. Below: rows of tiles. Row 1 is apps. The row with
  focus stays at a fixed height; rows above it slide away.
- **Focus model.** Spatial navigation with arrows, Enter, and Back. The focused tile stays pinned
  to the left edge of its row (until the row's end). Moving between rows picks the tile nearest
  horizontally. The focused tile scales up slightly with a white outline and a soft glow.
- **The one bold element: per-title tint.** Every title carries a tint color drawn from its art.
  The tint colors the focus glow, progress bars, and a soft wash in the bottom-left, and fades
  smoothly as focus moves. In the POC, derive the tint from the art automatically (the spike
  hand-picked them).
- **Type.** Instrument Serif for titles (display), Manrope for everything else. Both SIL OFL,
  bundled locally.
- **Palette** (oklch): abyss `0.15 0.035 268` (background), nebula `0.28 0.07 285`, starlight
  `0.97 0.012 85` (text), plus the per-title tint. No fixed accent color.
- **Motion.** One slow backdrop drift, a ~700 ms backdrop crossfade, a short hero text fade, and
  a launch transition that expands from the chosen tile. Respect reduced motion.
- **Scale.** All sizes in `rem`, with 1 rem derived from the viewport (16 px at 1080p, 32 px at
  4K). Layout never reflows between resolutions.

Known weak spot from the spike: the app launch screen (gradient, icon, title) is the plainest
screen and needs a stronger treatment.

## 5. Architecture

```
┌──────────────────────────── Vela OS (Fedora Atomic, bootc image) ─────────────────────────────┐
│ t2linux kernel + firmware   ·   autologin user session   ·   Wayland compositor (kiosk-style) │
│                                                                                                │
│  ┌───────────────┐   HTTP/WebSocket    ┌────────────────────────────┐                          │
│  │ Launcher UI   │ ◄─────────────────► │ vela-daemon                │ ── REST ──► Home Assistant│
│  │ (web app in a │                     │  · app manifests + launch   │             (container)   │
│  │  kiosk browser│                     │  · focus/window switching   │ ── API ───► Jellyfin/Plex │
│  │  window)      │                     │  · media data aggregation   │             servers (LAN) │
│  └───────────────┘                     │  · playback (mpv) + progress│ ── ir-ctl/LIRC ─► IR out  │
│                                        │  · Home-button handling     │                          │
│  Apps: Chrome (YouTube TV, Crunchyroll), Plex HTPC, Jellyfin Media Player, Steam, mpv          │
└────────────────────────────────────────────────────────────────────────────────────────────────┘
Input: Flirc USB (keyboard events), gamepads (evdev).   Output: HDMI 2.0, 3840x2160@60.
```

### 5.1 OS image

- A `Containerfile` producing a bootc image on Fedora Atomic. Installation media built with
  `bootc-image-builder`.
- **T2 support** is required: the t2linux kernel and the `apple-bce` driver (internal SSD,
  keyboard), plus Wi-Fi/Bluetooth firmware. Sources: the t2linux Fedora kernel and packages, and
  the community T2-Atomic project, which already builds Fedora Atomic images for T2 Macs. Whether
  to base on plain Fedora bootc, on a Universal Blue image, or on T2-Atomic is the builder's call
  (§10).
- Mac firmware prerequisite: T2 Macs must be set to allow booting other OSes (macOS Recovery →
  Startup Security Utility: no security, allow external boot). This is a one-time manual step and
  must be documented with photos or screenshots.
- No visible boot output: Plymouth with a Vela splash, quiet kernel.
- Updates via `bootc upgrade` with rollback. No update UI in the POC.

### 5.2 Session and compositor

The box autologins a dedicated user into a minimal Wayland session with no desktop. Every app
runs full screen. vela-daemon decides which window is in front.

**My call: Sway** for the POC. Its IPC (`swaymsg`) makes it easy to focus, hide, and full-screen
windows by app ID, to bind the Home key globally, and to hide the cursor. It runs fine on Intel.
Revisit KWin or gamescope later if HDR becomes a goal. The builder may pick a different kiosk
compositor if it meets the same needs: global Home key, programmatic window focus, no visible
chrome, 4K60.

### 5.3 Launcher UI

- A web app (TypeScript; framework is the builder's choice) served by vela-daemon on localhost
  and shown in a full-screen kiosk browser window.
- Implements the design language in §4 at native 4K.
- Talks to vela-daemon over HTTP/WebSocket for apps, rows, launch, device control, and status.
- **Performance budget:** 60 fps focus movement and crossfades on UHD 630 at 3840x2160.
  `backdrop-filter` blur and the continuous backdrop drift are the most likely costs. If the
  budget is missed, fall back in this order: pre-blurred tile backgrounds instead of live blur,
  then a static backdrop. The fallbacks must still look good.
- Art is always requested at display resolution: backdrops at 3840 px wide, posters and cards at
  the pixel size they render at on a 4K panel.

### 5.4 vela-daemon

One local service that owns everything the UI cannot do from a browser. Language is the builder's
choice; TypeScript is preferred so contributors can work across the UI and the daemon.

- **App manifests.** Each app is described by a manifest file (id, name, icon, launch command or
  URL plus browser profile, window app ID, and how Back/Home behave). Built-in manifests ship in
  the image; extra ones can be dropped into `/etc/vela/apps/`. This is the POC's extension point.
- **Launching and switching.** Starts an app if needed or brings its window to the front.
  Returns to the launcher on Home.
- **Media rows.** Pulls Continue Watching from Jellyfin (resume items) with backdrop art at
  3840 px. Plex rows are a stretch goal.
- **Playback.** Resuming a Jellyfin item must start playing in 4K with hardware decode and report
  progress back to Jellyfin. Builder's choice whether this goes through Jellyfin Media Player or
  through mpv directly with Jellyfin's direct-play URL and progress API.
- **Devices.** TV control through Home Assistant's REST API. IR through the local IR transmitter
  (§5.7).
- **Home button.** Owns the global "go home" behaviour from both the Flirc remote and gamepads.

### 5.5 The apps

| Tile | How it runs | Notes |
|---|---|---|
| YouTube | Google Chrome in app mode at `youtube.com/tv`, with a TV user agent and its own profile | The TV interface is built for remotes. 4K needs Chrome's VA-API hardware VP9 decode working on UHD 630. |
| Crunchyroll | Google Chrome in app mode, own profile | Needs Widevine, so it must be Google Chrome, not Fedora's Chromium. Resolution is capped by Widevine L3 (§7). |
| Plex | Plex HTPC (Flatpak) | Plex's own TV-style client, mpv-based, plays local 4K well. |
| Jellyfin | Jellyfin Media Player in TV mode (Flatpak) | mpv-based playback. |
| Steam | Steam Big Picture (`steam -gamepadui`) | Light games, or Remote Play from a gaming PC. |
| Home | A Vela-native screen (not the HA web UI) | TV power/volume, IR devices, and home status. |

Prefer Flatpaks for apps, preinstalled or installed on first boot (builder's choice).

### 5.6 Remote and input

- **Flirc USB** turns IR from the Flirc remote into keyboard events. Ship a Flirc config in the
  repo that maps: arrows, Enter/OK, Back → Esc, Home → a dedicated key the compositor grabs
  globally, play/pause and other media keys, and volume. Volume keys are sent to the TV (through
  Home Assistant or IR), not to the Mac's own audio output.
- **Gamepads** (Xbox, PlayStation, 8BitDo) must navigate the launcher. The Guide button goes
  home, except while Steam is in front, where Steam keeps it; a long press always goes home.
- **No mouse cursor**, ever, in normal use.

### 5.7 Smart home, TV, and IR

- **Home Assistant** runs as a container (Podman quadlet) on the box, with config in `/var`. In
  the POC it owns TV pairing and control through its Samsung (`samsungtv`) and Android TV
  (`androidtv_remote`) integrations. Pairing can be done once through HA's web UI from a phone.
- **IR out** is handled locally by vela-daemon, using the kernel IR stack (`ir-ctl`) or LIRC.
  The owner has two transmitters: a USB-C IR dongle (chipset unknown; may need a driver) and a
  headphone-jack-powered blaster (driven by the audio output, for example LIRC's audio driver).
  The POC needs one working; which one is the builder's call after probing the hardware.
- **IR codes** come from open datasets: Flipper-IRDB and probonopd/irdb.
- Later, IR devices should also be exposed to Home Assistant so automations can use them.

## 6. Setup in the POC

The POC can be configured with a config file plus a short documented checklist: update macOS
once, change the Mac firmware security setting, flash the USB stick, install (wiping the internal
SSD), enter the Jellyfin server and credentials, and pair the TVs in Home Assistant. The full
remote-and-phone first-run experience is a product goal, not a POC goal. Secrets (Jellyfin token,
Plex token) are stored on the box only, never in the repo.

**App sign-in (my call).** Each app is signed into once during setup and stays signed in. Use the
app's own code or QR sign-in where it has one: YouTube TV and Plex link with a code entered on a
phone, and Steam Big Picture offers QR sign-in. Where an app has none (Crunchyroll on the web), a
one-time sign-in with a temporarily plugged-in USB keyboard is acceptable and documented. An
on-screen keyboard is not POC work.

## 7. Constraints and known risks

| Risk | Impact | Mitigation |
|---|---|---|
| T2 support is community-maintained | Install could fail or break on kernel updates | Do a hardware bring-up first (§9, milestone 1); pin a known-good kernel. |
| UHD 630 at 4K with heavy CSS effects | Stutter (an anti-goal) | Measure early; fallbacks in §5.3. |
| Widevine L3 only on Linux | Crunchyroll (and some other DRM web video) is capped, often 720p to 1080p | Accepted. State it honestly in docs. |
| VA-API decode in Chrome on Linux | 4K YouTube may fall back to CPU and drop frames | Verify with `chrome://gpu` and real playback early. |
| YouTube TV user-agent approach | Google could break it | Accepted for a hobby project; keep the UA in the manifest so it's easy to change. |
| HDR on Intel under Linux is experimental | No HDR | HDR is a non-goal. |
| Unknown USB-C IR dongle chipset | May not work on Linux | Headphone-jack blaster is the fallback. |

## 8. How we'll know it works

- Frame timing measured on the real 2018 Mac mini at 3840x2160 (Chrome's performance tooling or
  equivalent) while scrolling every row.
- Screenshots captured at 3840x2160 of the home screen, a row change, and the Home screen panel,
  checked for softness.
- `chrome://gpu` and a 4K60 YouTube clip with the stats overlay showing no dropped frames; mpv
  showing `hwdec` active for a 4K HEVC file.
- A walkthrough of every POC goal in §3 using only the remote, recorded as a short checklist.

### 8.1 Building without the hardware (for now)

The builder has no access yet to the Mac mini, the TVs, or the IR hardware. So:

- Develop and test in an x86_64 VM (QEMU/UTM or similar) booting the same bootc image, with a
  virtual 3840x2160 display. Expect the VM to prove boot, session, launcher, and app switching,
  not GPU performance or hardware decode.
- Use a disposable Jellyfin server with openly licensed media (Blender open movies, including a
  4K HEVC file) for data and playback work.
- Put TV and IR control behind interfaces with mock implementations, so the launcher and daemon
  can be finished and tested without devices.
- Every check that needs real hardware (T2 boot, 4K60 over HDMI, 60 fps on UHD 630, VA-API decode,
  Flirc, TV pairing, IR out) ships as an **owner-run acceptance script** with clear pass/fail
  output. Each script collects logs and screenshots the owner can send back.
- Hardware milestones are marked "built, awaiting hardware verification" until the owner runs
  them. Access may come later; the plan should switch to direct testing when it does.

### 8.2 When a target is missed (my call)

- **Sharpness is a hard requirement.** Nothing ships rendered below native 4K.
- **60 fps and 4K playback are targets, measured on hardware.** A miss doesn't block the other
  milestones. Work through the fallbacks in §5.3, and record any remaining miss with numbers.
- **Swapping an approach is allowed** if the experience the owner sees stays the same, for
  example a different UI renderer or a different browser or player for one app. Replacing the
  YouTube TV interface with something worse to get 4K is not; raise that with the owner instead.

## 9. Suggested order

1. **Image and hardware bring-up:** the image boots in a VM, and the owner-run script checks
   that it boots the 2018 mini, outputs 4K60 over HDMI, and has network, Flirc input, and
   audio-jack output working.
2. **Session:** autologin to the compositor, launcher full screen, Home key works.
3. **Launcher at 4K** with mock data, performance measured on the mini.
4. **App launching and switching** for all six tiles.
5. **Jellyfin data and playback** with progress reporting.
6. **Home Assistant, TV control, and IR.**
7. **Install docs.**

## 10. Left to the builder

- Base image (plain Fedora bootc vs Universal Blue vs T2-Atomic) and how the T2 kernel is pulled in.
- UI framework, daemon language (TypeScript preferred), repo layout, and build tooling.
- Kiosk compositor, if not Sway (constraints in §5.2).
- Whether Jellyfin playback goes through Jellyfin Media Player or mpv.
- Which IR transmitter and which IR stack (`ir-ctl` vs LIRC).
- Flatpaks preinstalled in the image or installed on first boot.
- Exact app manifest format.
