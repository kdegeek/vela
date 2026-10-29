#!/usr/bin/env bats
# vela-ctl volume: which device gets the volume keys. curl is faked; it serves FAKE_DEVICES for
# GET /api/devices and records POSTed paths in FAKE_LOG.

setup() {
  bin="$(mktemp -d)"
  export FAKE_LOG="$bin/posts" FAKE_DEVICES="$bin/devices.json"
  cat > "$bin/curl" <<'SH'
#!/bin/sh
for a; do last=$a; done
case " $* " in
  *" -X POST "*) echo "${last#http://127.0.0.1:7300}" >> "$FAKE_LOG"; printf '\n204' ;;
  *) cat "$FAKE_DEVICES"; printf '\n200' ;;
esac
SH
  chmod +x "$bin/curl"
  export PATH="$bin:$PATH"
  ctl="$BATS_TEST_DIRNAME/vela-ctl"
}

@test "volume goes to the first device that has the command (the daemon lists the volume device first)" {
  echo '[{"id":"bar","kind":"ir","commands":["power","volume_up","volume_down"]},{"id":"tv","kind":"tv","commands":["power_on","volume_up","volume_down","mute"]}]' > "$FAKE_DEVICES"
  run "$ctl" volume up
  [ "$status" -eq 0 ]
  [ "$(cat "$FAKE_LOG")" = "/api/devices/bar/volume-up" ]
}

@test "a command the first device lacks goes to the next one that has it" {
  echo '[{"id":"bar","kind":"ir","commands":["volume_up"]},{"id":"tv","kind":"tv","commands":["volume_up","mute"]}]' > "$FAKE_DEVICES"
  run "$ctl" volume mute
  [ "$status" -eq 0 ]
  [ "$(cat "$FAKE_LOG")" = "/api/devices/tv/mute" ]
}

@test "IR codes named with dashes count" {
  echo '[{"id":"bar","kind":"ir","commands":["volume-down"]}]' > "$FAKE_DEVICES"
  run "$ctl" volume down
  [ "$status" -eq 0 ]
  [ "$(cat "$FAKE_LOG")" = "/api/devices/bar/volume-down" ]
}

@test "no device with the command is an error" {
  echo '[{"id":"amp","kind":"ir","commands":["power"]}]' > "$FAKE_DEVICES"
  run "$ctl" volume up
  [ "$status" -ne 0 ]
  echo "$output" | grep -q "no device with volume_up is configured"
  [ ! -e "$FAKE_LOG" ]
}
