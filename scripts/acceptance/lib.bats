#!/usr/bin/env bats
# Tests for lib.sh. Run: bats scripts/acceptance/lib.bats

setup() {
  export HOME="$BATS_TEST_TMPDIR/home"
  mkdir -p "$HOME"
  export VELA_REPORT_DIR="$BATS_TEST_TMPDIR/report"
  export VELA_BUNDLE_DIR="$HOME"
  # shellcheck source=lib.sh
  . "$BATS_TEST_DIRNAME/lib.sh"
}

@test "check prints PASS on success" {
  run check "true thing" true
  [ "$status" -eq 0 ]
  [ "$output" = "PASS true thing" ]
}

@test "check prints FAIL with the stderr tail" {
  noisy() { for i in 1 2 3 4 5 6; do echo "err line $i" >&2; done; return 3; }
  run check "noisy" noisy
  [ "$status" -ne 0 ]
  [[ "$output" == "FAIL noisy: "* ]]
  [[ "$output" == *"err line 6"* ]]
  [[ "$output" == *"err line 4"* ]]
  [[ "$output" != *"err line 1"* ]]
}

@test "check says what happened when a failing command prints nothing" {
  run check "silent" false
  [[ "$output" == "FAIL silent: "* ]]
}

@test "check counts failures and summary reports them" {
  check a true >/dev/null
  check b false >/dev/null 2>&1 || true
  warn c "optional thing missing" >/dev/null
  run summary
  [[ "$output" == *"1 passed"* ]]
  [[ "$output" == *"1 failed"* ]]
  [[ "$output" == *"1 warning"* ]]
}

@test "confirm honours VELA_CONFIRM" {
  VELA_CONFIRM=y run confirm "tv on" "Did it turn on?"
  [[ "$output" == *"PASS tv on"* ]]
  VELA_CONFIRM=n run confirm "tv on" "Did it turn on?"
  [[ "$output" == *"FAIL tv on"* ]]
}

@test "report_dir creates and returns the directory" {
  d=$(report_dir)
  [ "$d" = "$VELA_REPORT_DIR" ]
  [ -d "$d" ]
}

@test "bundle creates a tar.gz with the report files" {
  echo "hello" > "$(report_dir)/hw-check.txt"
  run bundle
  [ "$status" -eq 0 ]
  f=$(ls "$HOME"/vela-report-*.tar.gz)
  [[ "$f" =~ vela-report-[0-9]{8}-[0-9]{6}\.tar\.gz$ ]]
  [[ "$output" == *"$f"* ]]
  tar tzf "$f" | grep -q 'hw-check.txt'
}

@test "bundle redacts api_key and Token patterns" {
  printf 'GET /x?api_key=SEKRET123&y=1\nAuthorization Token=ABCDEF ok\n"homeAssistantToken": "LONGLIVED"\n' > "$(report_dir)/log.txt"
  run bundle
  [ "$status" -eq 0 ]
  f=$(ls "$HOME"/vela-report-*.tar.gz)
  mkdir -p "$BATS_TEST_TMPDIR/x" && tar xzf "$f" -C "$BATS_TEST_TMPDIR/x"
  out=$(cat "$BATS_TEST_TMPDIR"/x/*/log.txt)
  [[ "$out" != *SEKRET123* ]]
  [[ "$out" != *ABCDEF* ]]
  [[ "$out" != *LONGLIVED* ]]
  [[ "$out" == *"api_key=[redacted]"* ]]
}

@test "bundle never includes secrets.json" {
  echo '{"jellyfinToken":"nope"}' > "$(report_dir)/secrets.json"
  run bundle
  f=$(ls "$HOME"/vela-report-*.tar.gz)
  ! tar tzf "$f" | grep -q secrets.json
}

@test "locate_session finds the sway socket under XDG_RUNTIME_DIR" {
  rt="$BATS_TEST_TMPDIR/run"
  mkdir -p "$rt"
  touch "$rt/wayland-1" "$rt/sway-ipc.1000.4242.sock"
  unset WAYLAND_DISPLAY SWAYSOCK
  XDG_RUNTIME_DIR="$rt" locate_session
  [ "$WAYLAND_DISPLAY" = "wayland-1" ]
  [ "$SWAYSOCK" = "$rt/sway-ipc.1000.4242.sock" ]
}

# Each form must lose its secret; the log is bundled and the bundle read for the string.
redacted_bundle_has_no() { # redacted_bundle_has_no <line> <secret>
  printf '%s\n' "$1" > "$(report_dir)/log.txt"
  run bundle
  [ "$status" -eq 0 ]
  rm -rf "$BATS_TEST_TMPDIR/x" && mkdir -p "$BATS_TEST_TMPDIR/x"
  tar xzf "$(ls "$HOME"/vela-report-*.tar.gz | tail -n 1)" -C "$BATS_TEST_TMPDIR/x"
  out=$(cat "$BATS_TEST_TMPDIR"/x/*/log.txt)
  [[ "$out" != *"$2"* ]] || { echo "leaked: $out"; return 1; }
  rm -f "$HOME"/vela-report-*.tar.gz
}

@test "redaction: MediaBrowser header with quoted Token" {
  redacted_bundle_has_no 'Authorization: MediaBrowser Client="Vela", Device="box", DeviceId="1", Version="1", Token="s3cr3tA"' s3cr3tA
  redacted_bundle_has_no 'X-Emby-Authorization: MediaBrowser Client="Vela", Token="s3cr3tA2"' s3cr3tA2
  redacted_bundle_has_no 'MediaBrowser Client="Vela", Device="box", Token="s3cr3tA3"' s3cr3tA3
}
@test "redaction: X-Emby-Token and X-MediaBrowser-Token headers" {
  redacted_bundle_has_no 'X-Emby-Token: s3cr3tB' s3cr3tB
  redacted_bundle_has_no 'x-mediabrowser-token: s3cr3tB2' s3cr3tB2
}
@test "redaction: authorization header and JSON key in any case, any quoting" {
  redacted_bundle_has_no '"Authorization":"Bearer s3cr3tC"' s3cr3tC
  redacted_bundle_has_no "authorization: 'Bearer s3cr3tC2'" s3cr3tC2
  redacted_bundle_has_no 'AUTHORIZATION: Basic s3cr3tC3' s3cr3tC3
}
@test "redaction: Bearer token anywhere, any casing" {
  redacted_bundle_has_no 'sent header with BEARER s3cr3tD ok' s3cr3tD
  redacted_bundle_has_no 'got bearer s3cr3tD2.abc-def' s3cr3tD2
}
@test "redaction: api_key and apikey in URL and JSON forms" {
  redacted_bundle_has_no 'GET /Items?api_key=s3cr3tE&x=1' s3cr3tE
  redacted_bundle_has_no 'GET /Items?ApiKey=s3cr3tE2' s3cr3tE2
  redacted_bundle_has_no '{"api_key":"s3cr3tE3"}' s3cr3tE3
  redacted_bundle_has_no '{"apikey": "s3cr3tE4"}' s3cr3tE4
  redacted_bundle_has_no "{'api_key': 's3cr3tE5'}" s3cr3tE5
}
@test "redaction: token with = or : and optional quotes" {
  redacted_bundle_has_no 'Token=s3cr3tF' s3cr3tF
  redacted_bundle_has_no 'token: s3cr3tF2' s3cr3tF2
  redacted_bundle_has_no 'token="s3cr3tF3"' s3cr3tF3
  redacted_bundle_has_no '"homeAssistantToken": "s3cr3tF4"' s3cr3tF4
  redacted_bundle_has_no '"jellyfinToken":"s3cr3tF5"' s3cr3tF5
}

@test "locate_session replaces another user's XDG_RUNTIME_DIR with its own" {
  unset WAYLAND_DISPLAY SWAYSOCK
  export XDG_RUNTIME_DIR=/run/user/99999 WAYLAND_DISPLAY=wayland-9 SWAYSOCK=/run/user/99999/sway.sock
  locate_session
  [ "$XDG_RUNTIME_DIR" = "/run/user/$(id -u)" ]
  [ "${WAYLAND_DISPLAY:-}" != "wayland-9" ]
  [ "${SWAYSOCK:-}" != "/run/user/99999/sway.sock" ]
}

@test "confirm warns instead of failing when there is no TTY and no VELA_CONFIRM" {
  unset VELA_CONFIRM
  run confirm "tv on" "Did it turn on?" < /dev/null
  [[ "$output" == "WARN tv on: skipped (no TTY"* ]]
}
