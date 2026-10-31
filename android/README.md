# Vela for Android TV

The Android TV implementation from [the design](../docs/superpowers/specs/2026-10-30-vela-agentic-tv-design.md): an Android TV
app with an agent that can do anything on the TV. Sideloaded; it is not a Play Store app.

## Build

JDK 21 and the Android SDK (platform `android-37.0`, build-tools 37). Put the SDK path in `local.properties`
(`sdk.dir=...`) or `ANDROID_HOME`.

```
./gradlew testDebugUnitTest lintDebug assembleDebug     # JVM tests, then app/build/outputs/apk/debug/app-debug.apk
```

## Install on a box or emulator

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Install only on an authorized device. Then turn on its two permissions, either in the TV's settings (Accessibility › Vela agent; notification access ›
Vela) or over adb. **Add to the existing list, never replace it**: boxes often run other accessibility services, such as
a home-screen app.

```
cur=$(adb shell settings get secure enabled_accessibility_services)
new=app.vela.tv/.control.HandsService; [ "$cur" != null ] && [ -n "$cur" ] && new="$cur:$new"
adb shell settings put secure enabled_accessibility_services "$new"
adb shell settings put secure accessibility_enabled 1
adb shell cmd notification allow_listener app.vela.tv/app.vela.tv.control.MediaListener
```

Open Vela and scan the code on its home screen with a phone: that page sets the AI provider (an Anthropic key, or any
OpenAI-compatible server such as OpenAI, OpenRouter, Ollama or LM Studio), Jellyfin, Home Assistant, and whether Home
opens Vela. It also has a chat box.

## Testing on the emulator

A Google TV system image (`system-images;android-34;google-tv;arm64-v8a`, device `tv_4k`) runs headless with
`emulator -avd <name> -no-window`. Two things to know:

- `adb shell input keyevent` injects keys past accessibility services, so Vela never sees them. To press remote keys
  the way a real remote does, use `scripts/press.sh home`, which presses them through a virtual HID remote.
- The emulator reaches the Mac at `10.0.2.2`, so a model server on the Mac is `http://10.0.2.2:<port>/v1`.


## TV surfaces and design

Home, Search, Apps, My list, House, Activity and Settings are available from the navigation row.
Open a Jellyfin title to inspect details, choose an episode, resume, or save it to My list.
Settings contains phone pairing, permission shortcuts, day/night theme, reduced motion and high contrast.
The phone also provides a remote, connection checks, activity and layout editing.

See the [design system and release inventory](../docs/design/vela-tv-system.md) for component contracts,
backend limits, verification boundaries, and the 16 remaining release workstreams.

Jellyfin requires an address, API key **and user ID**. The player supports direct play on device-supported codecs;
adaptive/transcoding negotiation and full track preference support remain open.
Activity and agent conversations are session-local. Settings, layout and My list are persisted with atomic writes.

## Paired API

Every API request needs `X-Vela-Token` from the TV's pairing QR. Keep it private. The companion uses HTTP on the LAN;
HTTPS and individual phone revocation remain release work. Do not expose port 8765 to the internet.

| Method | Path | Behavior |
| --- | --- | --- |
| GET | `/api/state` | Redacted settings, capabilities, activity and current approval ID |
| POST | `/api/ask` | `{"text":"..."}` starts a task; returns `taskId` |
| POST | `/api/stop` | Cancel the current task |
| POST | `/api/answer` | `{"allow":false,"promptId":"..."}` answers exactly that prompt |
| POST | `/api/history/clear` | Stop and clear session-local conversations/activity |
| POST | `/api/remote` | `{"key":"up"}`; down/left/right/select/back/home also supported; agent must be idle |
| POST | `/api/settings` | Replace settings; masked credentials are preserved only for unchanged servers |
| POST | `/api/connections/test` | Check saved model/Jellyfin/Home Assistant connections; model check makes a small API request |
| GET / PUT | `/api/layout` | Read / validate and replace the composable layout |

Unauthorized requests return 401, malformed input 400, stale approvals 409, and body-read timeouts 408.
Bodies are limited to 256 KiB; owner requests to 8,000 characters.
The public HTML shell contains no pairing token. Use `/api/state`'s `pendingId` for approval responses;
legacy responses containing only `allow` are intentionally rejected.
