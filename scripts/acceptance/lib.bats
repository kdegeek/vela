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
