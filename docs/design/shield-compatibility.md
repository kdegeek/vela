# Shield compatibility target

Observed 2026-10-31 by the coordinator. Owner supplied the Shield at 10.0.0.209 with debugging enabled.
No other physical TV is included in this authorization.

Read-only ADB evidence:

- Transport: `10.0.0.209:5555`, connected and authorized.
- `ro.product.model`: `SHIELD Android TV`.
- `ro.product.device`: `sif`.
- Android release: `11`; API level: `30`.
- ABI list: `armeabi-v7a,armeabi` (32-bit ARM).
- No `app.vela.tv` package was installed when checked.
- The checkpoint debug APK includes `lib/armeabi-v7a/libandroidx.graphics.path.so`.

This target exercises the pre-API-33 accessibility click/focus fallback and a 32-bit runtime.
No inference about panel resolution, actual media codec support, Home interception or firmware unlock support has been validated.

T3 `device_list` currently discovers the emulator but does not list this physical Shield, despite its authorized ADB transport.
`device_open(hostId="local", deviceId="10.0.0.209:5555", platform="android")` returned:

> No device 10.0.0.209:5555 on 'This machine' (hostId local). Call device_list for current ids.

A fresh discovery still did not offer the Shield. Interactive testing is pending supported device routing.
Do not work around that with adb key events, screen capture, activity launching or scripts: session rules require
`device_open` and its returned agent-device command for UI actions. ADB remains permitted for builds, installs,
logs, port forwards and other operations the device route cannot perform.

No app installation, app launch, settings change, playback or Home-button takeover was performed during this connection check.
