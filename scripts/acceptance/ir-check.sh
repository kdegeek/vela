#!/bin/bash
# Vela IR transmitter check: lists the transmitters, sends a test code on each, you confirm.
# A phone camera shows an IR blaster's LED flashing; or watch the device react.
#   vela-ctl acceptance ir-check
set -u
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib.sh
. "$here/lib.sh"
begin ir-check

DAEMON_DIST=${VELA_DAEMON_DIST:-/usr/lib/vela/daemon/dist}

# The PipeWire headphone-jack sink, picked the way the daemon picks it (selectJackSink in
# audioJack.ts): an Audio/Sink whose name, description or nick says headphone, else analog; never
# HDMI. VELA_JACK_SINK (or ir.audioJackSink in vela.toml, for the daemon) overrides.
jack_sink() {
  if [ -n "${VELA_JACK_SINK:-}" ]; then printf '%s\n' "$VELA_JACK_SINK"; return; fi
  pw-dump 2>/dev/null | jq -r '
    def t: (.["node.name"] // "") + " " + (.["node.description"] // "") + " " + (.["node.nick"] // "");
    [.[] | select(.type == "PipeWire:Interface:Node" and .info.props["media.class"]? == "Audio/Sink")
     | .info.props | select(.["node.name"] != null) | select(t | test("hdmi|displayport"; "i") | not)]
    | (map(select(t | test("headphone"; "i"))) + map(select(t | test("analog"; "i"))))
    | .[0]["node.name"] // empty'
}

# render_nec_wav <out.wav>: a NEC burst (address 0x00, command 0xff) as the daemon's audio blaster
# waveform, rendered by the daemon's own code.
render_nec_wav() {
  node --input-type=module -e '
    const [dist, out] = process.argv.slice(1);
    const { encode } = await import(dist + "/devices/ir/encode.js");
    const { renderAudio, wavFromSamples } = await import(dist + "/devices/ir/audioJack.js");
    const fs = await import("node:fs");
    fs.writeFileSync(out, wavFromSamples(renderAudio(encode({ type: "parsed", name: "test", protocol: "NEC", address: 0, command: 255 }))));
  ' "$DAEMON_DIST" "$1"
}

shopt -s nullglob
lircs=(/dev/lirc*)
echo "Transmitters found:"
for l in "${lircs[@]}"; do echo "  LIRC device $l"; done
sink=$(jack_sink)
[ -n "$sink" ] && echo "  headphone-jack sink: $sink"
[ "${#lircs[@]}" -eq 0 ] && [ -z "$sink" ] && echo "  (none)"

check "at least one IR transmitter is present" test "${#lircs[@]}" -gt 0 -o -n "$sink"

for l in "${lircs[@]}"; do
  if command -v ir-ctl >/dev/null 2>&1; then
    # A fixed NEC scancode; any IR receiver sees the burst, a phone camera sees the LED.
    check "ir-ctl sends on $l" ir-ctl -d "$l" -S nec:0x00ff
    confirm "$l transmitted" "Did the blaster on $l flash (phone camera) or the target device react?"
  else
    check "ir-ctl is installed" false
  fi
done

# The headphone-jack blaster, raw: the burst goes to the jack sink only (not the TV's HDMI sound).
if [ -n "$sink" ]; then
  wav="$(report_dir)/ir-jack-nec.wav"
  if check "render a NEC burst for the jack" render_nec_wav "$wav"; then
    echo "Sending a NEC test burst on $sink (the TV's speakers should stay silent)."
    check "pw-play sends on the headphone-jack sink" pw-play --target "$sink" --volume 1.0 "$wav"
    confirm "jack blaster transmitted" "Did the headphone-jack blaster flash (phone camera) or the target device react, with no sound from the TV?"
  fi
else
  warn "headphone-jack sink" "no analog/headphone PipeWire sink; set VELA_JACK_SINK=<node name from wpctl status> to pick one"
fi

# The daemon's own path: the first command of each configured IR device, on the transmitter it names.
if api_get /api/health >/dev/null 2>&1; then
  devices=$(api_get /api/devices 2>/dev/null || echo '[]')
  mapfile -t irs < <(printf '%s' "$devices" | jq -r '.[] | select(.kind == "ir" and (.commands | length) > 0) | "\(.id) \(.commands[0])"')
  if [ "${#irs[@]}" -eq 0 ]; then
    warn "configured IR devices" "no kind=\"ir\" device in vela.toml; only the raw transmitter test ran"
  fi
  for line in "${irs[@]}"; do
    id=${line%% *}
    cmd=${line#* }
    check "daemon sends $id/$cmd" api_post "/api/devices/$id/$cmd"
    confirm "$id/$cmd reached the device" "Did the device respond to '$cmd'?"
  done
else
  warn "daemon" "vela-daemon is not answering; skipped the configured-device test"
fi
finish
