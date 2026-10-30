# Vela for Android TV

The phase-one prototype from [the design](../docs/superpowers/specs/2026-10-30-vela-agentic-tv-design.md): an Android TV
app with an agent that can do anything on the TV. Sideloaded; it is not a Play Store app.

## Build

JDK 21 and the Android SDK (platform `android-37.0`, build-tools 37). Put the SDK path in `local.properties`
(`sdk.dir=...`) or `ANDROID_HOME`.

```
./gradlew testDebugUnitTest assembleDebug     # JVM tests, then app/build/outputs/apk/debug/app-debug.apk
```

## Install on a box or emulator

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then turn on its two permissions, either in the TV's settings (Accessibility › Vela agent; notification access ›
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
