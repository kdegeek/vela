# Integrated acceptance — 2026-10-31

Vela is currently a sideloaded Android TV shell, not a finished firmware image.
Evidence below comes from the coordinator's Mac, native Safari and the Google TV
Android 14 emulator. No APK was installed on the owner's Shield.

## Final local checks

| Check | Observed result |
| --- | --- |
| `./gradlew testDebugUnitTest lintDebug assembleDebug` | **396 JVM tests passed**, no failures; lint has 10 warnings and no errors; APK assembled |
| `pnpm typecheck` | Passed, no Svelte errors or warnings |
| `pnpm test` | **303 passed**, 3 skipped |
| Shipped phone script `node --test android/app/src/test/ui/phone.test.cjs` | **35 passed** |
| Local fixture `python3 -m unittest discover -s dev/tv-fixtures` | **19 passed** |
| Current real Jellyfin/Home Assistant clients against loopback fixture | Libraries, search, 210 episodes, season paging, next-up, direct-play negotiation, exact seek bytes, authenticated VTT, progress writes and counted home action passed |
| Nine TV destinations in day/night | Captured and inspected on a 1920×1080 app surface; empty/disconnected states included |
| Native profile/memory/routine editors | Captured; scrolling and Back exercised; a D-pad move reached a text field; complete traversal remains pending |
| Live paired management API | Profile create, private memory save/list, stale-edit 409, profile isolation, switch/drain, delete and cleanup passed |
| Current phone HTML in native Safari | Connected, loaded profiles and opened an unsaved editor; rendered at observed 769- and 574-pixel web-area widths in dark appearance |

The native TV captures and live paired API check precede the final Now Playing,
Plex and control/media corrections. The final combined APK compiles those changes,
but it has not been relaunched under the interrupted device-control route.
The browser loaded the current HTML through a read-only local proxy; all writes
were rejected, and its public synthetic browser token was separate from pairing.
This proves rendering/read/form-open behavior, not fresh APK/browser mutation acceptance.

The initial emulator model configuration uses a scripted `fake` endpoint. No
result here establishes real-model reasoning quality, paid-provider latency or
real Jellyfin/Plex/Home Assistant interoperability.

## Visual evidence

[TV day contact sheet](evidence/tv-day.png) · [TV night contact sheet](evidence/tv-night.png)

Rows show Home, Search, Apps, My list, House, Activity, Profiles, Routines and
External control. These contain no pairing/external credential. They document the
base shell before the later Now Playing banner/Plex integration; those additions
still require native visual acceptance. Detailed local captures are under
`/tmp/vela-ui-acceptance-20261031/`.

Safari QA used the existing **Nate’s Mac mini** CUA shared-desktop Space, in a
separate task window. Isolated Chromium launch was refused with **“no vendor-signed
system Chromium executable is available for isolated launch.”** Safari clamped a
requested 390-pixel window to 574 pixels; 320/390 CSS widths, 200% zoom and light
appearance are not claimed. The task window and driver session were closed; the
read-only proxy and fixture server were stopped. No browser installation or
security/profile setting was changed.

## Integration corrections

The initial review and subsequent probes led to 22 recorded control, transport,
persistence and media corrections. [Security findings](security-findings.md)
records each disposition. The final suite includes selected-leaf purchase labels,
prepared target/configuration invalidation, Stop/drain ownership, Main-confined
controller admission, external Busy semantics, context bounds, one-attempt action
POSTs, verified routine commits, cancellation at reservation handoff, profile-bound
routines, exact media-session references and truthful playback outcomes.

All nine deterministic generated properties now pass unchanged. They exposed an
MCP SSE result-loss race that first reproduced under Ktor test-host; writer-owned
cleanup fixes it. Real CIO regressions cover sequential responses, disconnect,
Stop/revoke drain and listener start/stop/reopen. No pre-fix CIO loss or exactly-once
network delivery is claimed. See [stream correction](mcp-stream-correction.md)
and [media targets](media-target-contract.md).

## Device-tool interruption

After finding the MCP listener startup hang, the bound device command
`open app.vela.tv --relaunch --foreground` reported **“Android app app.vela.tv
process remained running after close.”** Its prescribed `--debug` retry returned
the same error (`reason: android-app-close-timeout`, 2000 ms). The coordinator
stopped that interactive path. No raw ADB launch, force-stop, input or screenshot
workaround was used. The separately reproduced listener hang is fixed in code;
that does not establish that the device-tool lifecycle failure is fixed.

## Remaining acceptance queue — 6 items

1. **Native device UI/lifecycle:** resume a working device route; validate final Now Playing/Plex changes, Settings/Ask/title sheets, populated/error states, D-pad/focus restoration, large fonts and both-theme editors.
2. **Narrow phone/accessibility:** 320/390 CSS widths, 200% zoom, light appearance, keyboard traversal, confirmations and export/download on a suitable controllable browser.
3. **Live routines/contention:** assembled-app schedules, durable reopen/retry, busy queue, exact cancellation, restart and profile-switch drain under safe tasks. Domain regressions already pass.
4. **Live assembled MCP:** corrected enable/tool/revoke flow and revocation during work, independent of socket/domain tests.
5. **Real services/models:** approved Jellyfin/Plex/Home Assistant endpoints and secure provider credentials; representative reasoning, latency, codec and error scenarios.
6. **Hardware/firmware/release:** authorized Shield/Google TV tests, selected vendor/BSP, native 4K, performance/soak, accessibility, CEC/power, signed updates/recovery and distribution qualification.

Items 1–4 can advance from repository context once device/browser control is usable.
Item 5 needs external endpoints/secure credentials. Item 6 needs hardware/vendor and
release decisions plus authorization for any destructive firmware operation.
The broader product inventory remains in [Vela TV system](vela-tv-system.md).
