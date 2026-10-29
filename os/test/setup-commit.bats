#!/usr/bin/env bats
# os/files/usr/libexec/vela/setup-commit against a temp tree. ssh-keygen is real (keys are made in
# setup); check-config.js (node) and systemctl are stubs that log their arguments. Runs as the
# current user, so "owned by vela" means owned by whoever runs the tests.

SCRIPT="$BATS_TEST_DIRNAME/../files/usr/libexec/vela/setup-commit"

setup() {
  T=$(mktemp -d)
  export VELA_SETUP_GATE="$T/run/vela-setup/open" VELA_SETUP_STAGE="$T/stage"
  export VELA_CONFIG_DEST="$T/etc/vela/vela.toml" VELA_ROOT_SSH_DIR="$T/roothome/.ssh"
  export VELA_SETUP_WORK="$T" VELA_SETUP_AS_OWNER="" VELA_SETUP_OWNER
  VELA_SETUP_OWNER=$(id -un)
  export FAKE_LOG="$T/calls" FAKE_CHECK_STATUS=0
  mkdir -p "$T/bin" "$T/run/vela-setup" "$VELA_SETUP_STAGE"
  cat > "$T/bin/node" <<'SH'
#!/bin/sh
echo "node $*" >> "$FAKE_LOG"
[ "$FAKE_CHECK_STATUS" = 0 ] || echo "Invalid config: media.provider: bad" >&2
exit "$FAKE_CHECK_STATUS"
SH
  cat > "$T/bin/systemctl" <<'SH'
#!/bin/sh
echo "systemctl $*" >> "$FAKE_LOG"
SH
  chmod +x "$T/bin/node" "$T/bin/systemctl"
  export VELA_NODE="$T/bin/node" VELA_SYSTEMCTL="$T/bin/systemctl"
  date +%s > "$VELA_SETUP_GATE"
  ssh-keygen -q -t ed25519 -N '' -C phone -f "$T/k1" >/dev/null
  ssh-keygen -q -t ed25519 -N '' -C laptop -f "$T/k2" >/dev/null
  K1=$(cat "$T/k1.pub") K2=$(cat "$T/k2.pub")
  printf '[media]\nprovider = "mock"\n' > "$VELA_SETUP_STAGE/vela.toml"
}

teardown() { rm -rf "$T"; }

stage_keys() { printf '%s\n' "$@" > "$VELA_SETUP_STAGE/authorized_keys"; }
commit() { touch "$VELA_SETUP_STAGE/commit"; run "$SCRIPT"; }
mode() { if stat -c %a "$1" 2>/dev/null; then :; else stat -f %Lp "$1"; fi; }

# Refused: nothing installed, setup still open, staged files left for a retry, commit gone.
assert_refused() {
  [ "$status" -eq 0 ]
  [[ "$output" == *"REFUSED"* ]]
  [ ! -e "$VELA_CONFIG_DEST" ]
  [ ! -e "$VELA_ROOT_SSH_DIR/authorized_keys" ]
  [ -e "$VELA_SETUP_GATE" ]
  [ ! -e "$VELA_SETUP_STAGE/commit" ]
  ! grep -q restart "$FAKE_LOG" 2>/dev/null
}

@test "installs vela.toml and the keys, closes setup, clears the stage, restarts the daemon" {
  stage_keys "$K1" "$K2"
  commit
  [ "$status" -eq 0 ]
  [ "$(cat "$VELA_CONFIG_DEST")" = "$(printf '[media]\nprovider = "mock"')" ]
  [ "$(mode "$VELA_CONFIG_DEST")" = 644 ]
  [ "$(cat "$VELA_ROOT_SSH_DIR/authorized_keys")" = "$(printf '%s\n%s' "$K1" "$K2")" ]
  [ "$(mode "$VELA_ROOT_SSH_DIR/authorized_keys")" = 600 ]
  [ "$(mode "$VELA_ROOT_SSH_DIR")" = 700 ]
  [ ! -e "$VELA_SETUP_GATE" ]
  [ ! -e "$VELA_SETUP_STAGE/commit" ]
  [ ! -e "$VELA_SETUP_STAGE/vela.toml" ]
  [ ! -e "$VELA_SETUP_STAGE/authorized_keys" ]
  grep -qx "node /usr/lib/vela/daemon/dist/check-config.js $T/vela-setup-commit\..*/vela.toml" "$FAKE_LOG"
  grep -qx "systemctl --user -M vela@ restart vela-daemon.service" "$FAKE_LOG"
  [ -z "$(find "$T" -maxdepth 1 -name 'vela-setup-commit.*')" ]
}

@test "skipped SSH: installs vela.toml and leaves root's keys alone" {
  commit
  [ "$status" -eq 0 ]
  [ -f "$VELA_CONFIG_DEST" ]
  [ ! -e "$VELA_ROOT_SSH_DIR" ]
  [ ! -e "$VELA_SETUP_GATE" ]
  grep -q "restart vela-daemon.service" "$FAKE_LOG"
}

@test "keys from the VELA stick are kept and a repeated key is not added twice" {
  mkdir -p "$VELA_ROOT_SSH_DIR"
  printf 'from="10.0.0.0/8" %s\n%s' "$K1" "# no final newline" > "$VELA_ROOT_SSH_DIR/authorized_keys"
  stage_keys "$K1" "$K2"
  commit
  [ "$status" -eq 0 ]
  [ "$(cat "$VELA_ROOT_SSH_DIR/authorized_keys")" = "$(printf 'from="10.0.0.0/8" %s\n# no final newline\n%s' "$K1" "$K2")" ]
  [[ "$output" == *"1 key(s) added, 2 staged"* ]]
}

@test "a previous vela.toml is kept next to the new one" {
  mkdir -p "$(dirname "$VELA_CONFIG_DEST")"
  echo old > "$VELA_CONFIG_DEST"
  commit
  [ "$status" -eq 0 ]
  [ "$(cat "$VELA_CONFIG_DEST.before-setup")" = old ]
  grep -q mock "$VELA_CONFIG_DEST"
}

@test "setup closed: refuses, installs nothing, removes commit" {
  rm "$VELA_SETUP_GATE"
  stage_keys "$K1"
  commit
  [ "$status" -eq 0 ]
  [[ "$output" == *"setup is not open"* ]]
  [ ! -e "$VELA_CONFIG_DEST" ]
  [ ! -e "$VELA_ROOT_SSH_DIR" ]
  [ ! -e "$VELA_SETUP_STAGE/commit" ]
  [ ! -e "$FAKE_LOG" ]
}

@test "a symlinked staged file is refused" {
  rm "$VELA_SETUP_STAGE/vela.toml"
  printf '[media]\nprovider = "mock"\n' > "$T/elsewhere.toml"
  ln -s "$T/elsewhere.toml" "$VELA_SETUP_STAGE/vela.toml"
  commit
  assert_refused
  [[ "$output" == *"vela.toml is a symlink"* ]]
}

@test "a symlinked authorized_keys is refused" {
  printf '%s\n' "$K1" > "$T/keys"
  ln -s "$T/keys" "$VELA_SETUP_STAGE/authorized_keys"
  commit
  assert_refused
  [[ "$output" == *"authorized_keys is a symlink"* ]]
}

@test "a symlinked staging dir is refused and removed, and nothing is deleted through it" {
  mv "$VELA_SETUP_STAGE" "$T/real-stage"
  touch "$T/real-stage/commit"
  ln -s "$T/real-stage" "$VELA_SETUP_STAGE"
  run "$SCRIPT"
  assert_refused
  [ ! -L "$VELA_SETUP_STAGE" ]
  [ -e "$T/real-stage/commit" ]
}

@test "a staged directory instead of a file is refused" {
  mkdir "$VELA_SETUP_STAGE/authorized_keys"
  commit
  assert_refused
  [[ "$output" == *"not a regular file"* ]]
}

@test "a key line ssh-keygen can't parse is refused" {
  stage_keys "$K1" "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINotARealKeyAtAll== broken"
  commit
  assert_refused
  [[ "$output" == *"line 2 is not a valid SSH public key"* ]]
}

@test "a line that isn't a key is refused" {
  stage_keys "hello world"
  commit
  assert_refused
  [[ "$output" == *"line 1 is not '<type> <base64> [comment]'"* ]]
}

@test "a key type outside the allowed list is refused" {
  stage_keys "ssh-dss ${K1#ssh-ed25519 }"
  commit
  assert_refused
}

@test "an option prefix is refused" {
  stage_keys "command=\"/bin/sh\" $K1"
  commit
  assert_refused
  [[ "$output" == *"no options"* ]]
}

@test "more than 16 keys are refused" {
  lines=()
  for _ in $(seq 17); do lines+=("$K1"); done
  stage_keys "${lines[@]}"
  commit
  assert_refused
  [[ "$output" == *"more than 16 keys"* ]]
}

@test "16 keys with blank and comment lines are fine" {
  lines=("# from the phone" "")
  for _ in $(seq 16); do lines+=("$K1"); done
  stage_keys "${lines[@]}"
  commit
  [ "$status" -eq 0 ]
  [ "$(grep -c . "$VELA_ROOT_SSH_DIR/authorized_keys")" = 1 ]
}

@test "an authorized_keys with no keys is refused" {
  stage_keys "# nothing here"
  commit
  assert_refused
}

@test "a vela.toml check-config rejects is refused, with its reason" {
  export FAKE_CHECK_STATUS=1
  stage_keys "$K1"
  commit
  assert_refused
  [[ "$output" == *"does not pass check-config: Invalid config: media.provider: bad"* ]]
}

@test "a missing vela.toml is refused" {
  rm "$VELA_SETUP_STAGE/vela.toml"
  commit
  assert_refused
  [[ "$output" == *"vela.toml is missing"* ]]
}

@test "a staged file over 64 KB is refused" {
  head -c 70000 /dev/zero | tr '\0' '#' > "$VELA_SETUP_STAGE/vela.toml"
  commit
  assert_refused
  [[ "$output" == *"bigger than 65536 bytes"* ]]
}

@test "a staging dir owned by someone else is refused" {
  export VELA_SETUP_OWNER=nobody
  commit
  assert_refused
  [[ "$output" == *"not a directory owned by nobody"* ]]
}

@test "a failed daemon restart fails the run after installing" {
  printf '#!/bin/sh\nexit 1\n' > "$T/bin/systemctl"
  commit
  [ "$status" -ne 0 ]
  [ -f "$VELA_CONFIG_DEST" ]
  [ ! -e "$VELA_SETUP_GATE" ]
  [[ "$output" == *"could not restart vela-daemon"* ]]
}

@test "a gate opened more than 2 hours ago is refused as expired" {
  echo $(($(date +%s) - 7201)) > "$VELA_SETUP_GATE"
  stage_keys "$K1"
  commit
  assert_refused
  [[ "$output" == *"REFUSED: setup expired"* ]]
}

@test "a gate without a readable opening time is refused as expired" {
  for bad in "" "soon" "0$(date +%s)" "$(($(date +%s) + 3600))"; do
    printf '%s' "$bad" > "$VELA_SETUP_GATE"
    commit
    assert_refused
    [[ "$output" == *"REFUSED: setup expired"* ]]
  done
}

@test "a gate opened 1 hour 59 minutes ago is still open" {
  echo $(($(date +%s) - 7140)) > "$VELA_SETUP_GATE"
  commit
  [ "$status" -eq 0 ]
  [ -f "$VELA_CONFIG_DEST" ]
  [ ! -e "$VELA_SETUP_GATE" ]
  grep -qx "systemctl --no-block stop vela-setup-expire.timer" "$FAKE_LOG"
}
