#!/usr/bin/env bats
# os/files/usr/libexec/vela/setup-gate: opening setup with its time, and closing it after 2 hours.
# systemctl is a stub that logs its arguments.

SCRIPT="$BATS_TEST_DIRNAME/../files/usr/libexec/vela/setup-gate"

setup() {
  T=$(mktemp -d)
  export VELA_SETUP_GATE="$T/run/vela-setup/open" FAKE_LOG="$T/calls"
  printf '#!/bin/sh\necho "systemctl $*" >> "$FAKE_LOG"\n' > "$T/systemctl"
  chmod +x "$T/systemctl"
  export VELA_SYSTEMCTL="$T/systemctl"
}

teardown() { rm -rf "$T"; }

expired() { echo "$T/run/vela-setup/expired"; }

@test "open writes the opening time, drops an old expired marker and arms the timer" {
  mkdir -p "$T/run/vela-setup" && echo 1 > "$(expired)"
  run "$SCRIPT" open
  [ "$status" -eq 0 ]
  now=$(date +%s)
  opened=$(cat "$VELA_SETUP_GATE")
  [ "$opened" -le "$now" ] && [ "$opened" -ge $((now - 5)) ]
  [ ! -e "$(expired)" ]
  grep -qx "systemctl --no-block restart vela-setup-expire.timer" "$FAKE_LOG"
}

@test "expire leaves a fresh gate open" {
  "$SCRIPT" open
  run "$SCRIPT" expire
  [ "$status" -eq 0 ]
  [ -e "$VELA_SETUP_GATE" ]
  [ ! -e "$(expired)" ]
  [[ "$output" == *"not expired yet"* ]]
}

@test "expire closes a gate opened 2 hours ago and leaves the expired marker" {
  "$SCRIPT" open
  old=$(($(date +%s) - 7200))
  echo "$old" > "$VELA_SETUP_GATE"
  run "$SCRIPT" expire
  [ "$status" -eq 0 ]
  [ ! -e "$VELA_SETUP_GATE" ]
  [ "$(cat "$(expired)")" = "$old" ]
  grep -qx "systemctl --no-block stop vela-setup-expire.timer" "$FAKE_LOG"
}

@test "expire closes a gate whose time is missing, malformed or far in the future" {
  for bad in "" "soon" "0123" "$(($(date +%s) + 3600))"; do
    mkdir -p "$T/run/vela-setup"
    printf '%s' "$bad" > "$VELA_SETUP_GATE"
    run "$SCRIPT" expire
    [ "$status" -eq 0 ]
    [ ! -e "$VELA_SETUP_GATE" ]
    [ -e "$(expired)" ]
  done
}

@test "expire with setup already closed just stops the timer" {
  run "$SCRIPT" expire
  [ "$status" -eq 0 ]
  [ ! -e "$(expired)" ]
  [ "$(cat "$FAKE_LOG")" = "systemctl --no-block stop vela-setup-expire.timer" ]
}
