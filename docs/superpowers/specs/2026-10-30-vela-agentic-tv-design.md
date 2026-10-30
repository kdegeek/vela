# Vela, agentic TV box: design

Status: building, 2026-10-30. Supersedes the Mac mini direction in
[2026-10-28-vela-design.md](2026-10-28-vela-design.md) as the product line; that document's design
language, "never get stuck" rule, and media/TV-control ideas carry over.

## 1. What it is

A Kickstarter-funded TV streaming and home box whose main feature is an AI agent that can do
anything on the TV for you. The owner doesn't need to know how to do anything: say what you want
and the agent opens the app, finds the show, plays it, dims the lights. The home screen is
composable: the agent (or the owner) can rebuild its rows, sources and widgets.

## 2. Decisions made by the owner

| Area | Decision |
|---|---|
| Order | **Software first.** An Android TV app on stock Google TV boxes (the owner has test boxes). It is the Kickstarter prototype. Hardware is phase two. |
| Hardware route | **Open firmware on imported hardware (AOSP, no Google certification).** Privileged system-app agent. Netflix, Disney+ and Prime in HD are not expected at launch; distribution deals are pursued if the project gets big enough. Until then the focus is Plex, a custom Jellyfin front end, YouTube and the like. |
| Business | **Open-core.** The app and firmware are open; the hosted model gateway (and the box) are paid. |
| 4K | **A must.** The interface renders at native 3840x2160. Art is always requested at 4K. |
| AI | Any provider: an OpenAI-compatible endpoint (OpenAI, OpenRouter, Ollama, LM Studio, vLLM, our gateway) or Anthropic's API, with the owner's key. "Local" means a model server on the owner's network, not on the box. Claude and ChatGPT *subscriptions* cannot be used in a third-party app (Anthropic forbids it; OpenAI only through its partner programme). A later route for subscription users: the box is also an MCP server on the LAN, so Claude Desktop or Claude Code on the same network can drive the TV (claude.ai web and mobile only if the server is exposed beyond the LAN). |
| Guardrails | Virtually none. The agent asks on the TV before: purchases (including rentals, trials and subscription sign-ups); locks, alarms, garage doors and cameras; account and password changes; installing apps; factory reset. Everything else just happens. The reason is prompt injection from on-screen text, not distrust of the owner. |

## 3. Goals for phase one (the prototype)

On a stock Google TV box with only the remote and a phone:

1. **Vela is the home screen.** The Home button opens Vela (accessibility key interception, the way
   Projectivy does it), with a cinematic home in the Vela design language. Google is closing this
   route on stock Google TV; if Home interception doesn't survive on the test box, Vela is reached
   by another remapped key or its own tile, and owning Home is guaranteed only on Vela firmware.
2. **Talk to it.** A remote button opens the agent; speech or typing (phone page) gives it a task.
   The agent's steps show in an overlay on whatever app is on screen.
3. **It can do anything a person can.** Read any app's screen (accessibility tree), move focus,
   click, go back, go home, open apps, deep links and in-app search, control playback of any app's
   media session.
4. **It prefers direct routes.** Deep links, search intents and media sessions before stepping
   through screens.
5. **Composable home.** The home screen is a JSON layout the agent can read and rewrite (rows of
   apps, Jellyfin, Plex, pinned titles, widgets), and it persists.
6. **Home control.** Home Assistant services through the owner's Home Assistant.
7. **Set up from a phone.** A page served by the box on the LAN (QR code on the TV) for provider,
   keys, model, Home Assistant and Jellyfin, plus a chat box.
8. **Guardrails** as in §2, confirmed on the TV with the remote.

Later milestones: custom Jellyfin front end with a Media3 player and progress reporting; Plex rows;
MCP server; Vela firmware (AOSP on an RK3588-class SoC with 4K UI, privileged key injection, silent
installs and screen capture).

## 4. Non-goals for phase one

DRM service deals; our own hardware; on-box model inference; multiple profiles; an app store for
widgets; the Play Store listing (Play's accessibility policy restricts this use; the app is
sideloaded).

## 5. Anti-goals

Everything in the 2026-10-28 document (stutter, softness, stuck states, templated look), plus:

- **The agent is slow where a direct route exists.** "Play X" must not take a dozen D-pad steps.
- **The agent does something irreversible or costly without asking** (the §2 categories).
- **Secrets leave the box** for anywhere but the provider the owner chose.

## 6. Architecture (phase one)

One Android app, `android/` in this repo, Kotlin + Jetpack Compose (Compose for TV components).

- `launcher` – the home activity (HOME + LEANBACK_LAUNCHER), rendered from the layout document.
- `layout` – the composable home: a versioned JSON document of rows, each with a data source.
- `agent` – the loop: provider client (OpenAI-compatible chat completions or Anthropic Messages,
  both with tool calling), tool registry, step limit, transcript.
- `control` – the accessibility service: compact screen tree, node actions, global actions, D-pad
  (API 33+ global D-pad actions, focus/click actions below that), Home interception, the step
  overlay and confirmation prompts. A notification listener grants media session access.
- `home` – Home Assistant REST client. `media` – Jellyfin client.
- `web` – a small LAN HTTP server for the phone page (settings and chat) and the agent API.
- `guard` – classifies tool calls and click targets into the §2 categories and requires a remote
  confirmation.

Settings and secrets live in the app's private storage on the box.

## 7. Calls made by the builder (not the owner)

- **4K on stock boxes.** Many Google TV boxes give third-party apps a 1080p surface even on a 4K
  panel. If the test box does (measured with `wm size`), phase one accepts it for the prototype
  only; every size is in dp, so the same layout is sharp at 4K on Vela firmware.
- Kotlin + Compose rather than a WebView of the old Svelte launcher: cheap TV SoCs scroll a 4K
  WebView poorly, and the agent's hands (accessibility) are native anyway.
- Milestone 0 is a bare accessibility service that answers three questions on the real box before
  the launcher and agent are built: does Home reach it, how much of YouTube's and other apps'
  screens the tree exposes, and whether node clicks land.

## 8. Test box facts (2026-10-30)

The owner's Google TV boxes haven't been connected yet. Two devices were measured:

| Device | Android | Panel | Surface apps get |
|---|---|---|---|
| Fire TV smart TV (AFTTIFF43, Fire OS 7.7) | 9 (API 28) | 3840x2160 | 1920x1080 at 320 dpi |
| Google TV emulator (`android-34;google-tv`, `tv_4k`) | 14 (API 34) | 3840x2160 | 1920x1080 at 320 dpi |

**4K on stock software:** both give third-party apps a 1080p surface on a 4K panel. A native 4K interface needs Vela
firmware (or a box that doesn't override the size). The layout is in dp, so it is unchanged at 4K.

**Milestone 0 answers (Google TV emulator):**

1. **Home reaches the accessibility service.** With "Home opens Vela" on, Home from inside YouTube opens Vela's home
   screen. This is the emulator image, not a box with the 2026 Google TV update that broke Projectivy; check again on a
   real box. On the Fire TV, a third-party home app (tvhome) already replaces Home the same way.
2. **Screens are readable.** Google TV's own setup screen came through as buttons with text and ids
   (`[15] Button "Cancel" #vanilla_exit_cancel {focused,click}`). A playing SmartTube video showed nothing readable
   (the app draws its own surface), as expected; its controls weren't tested.
3. **Clicks:** a plain accessibility click on a TV guided-step button only moved focus. Presses now focus the element
   and send the remote's OK (global D-pad action, Android 13+), which is how a remote does it.

**Testing note:** `adb shell input keyevent` never reaches accessibility services (injected events skip the filter), so
remote keys are tested through a virtual HID remote made with the shell's `hid` command.

**End to end on the emulator** (scripted OpenAI-compatible stand-in for the model): the agent read the screen, pressed
Cancel, tried to open a Play Store link, which the guard resolved to `com.android.vending` and put to the owner on the
TV; Back on the remote declined it and the agent was told not to try another way; OK on a second run allowed it and the
Play Store opened. The agent then rewrote the home layout and the new row appeared. Not yet run against a real model:
no key or LAN model server was available in this session.
