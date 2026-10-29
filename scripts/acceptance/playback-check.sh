#!/bin/bash
# Vela 4K playback check: hardware decode in mpv, a Jellyfin title through Vela (resume position and
# progress reporting), then YouTube TV at 2160p.
#   vela-ctl acceptance playback-check [path/to/4k-hevc-10bit-file]
# With no path, a 20 s test clip is generated with ffmpeg:
#   ffmpeg -f lavfi -i testsrc2=s=3840x2160:r=60 -t 20 -c:v libx265 -pix_fmt yuv420p10le /var/tmp/vela-4k-hevc10.mkv
set -u
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib.sh
. "$here/lib.sh"
begin playback-check

file=${1:-}
YT_URL=${VELA_YT_TEST_URL:-https://www.youtube.com/tv#/watch?v=LXb3EKWsInQ}
dir=$(report_dir)

if [ -z "$file" ]; then
  file=/var/tmp/vela-4k-hevc10.mkv
  if [ ! -s "$file" ]; then
    if command -v ffmpeg >/dev/null 2>&1; then
      info "generating a 20 s 4K HEVC 10-bit test clip at $file (takes a minute or two)"
      # Write to a temp name first so a truncated clip is never reused.
      ffmpeg -loglevel error -y -f lavfi -i testsrc2=s=3840x2160:r=60 -t 20 -c:v libx265 -pix_fmt yuv420p10le -f matroska "$file.part" \
        && mv "$file.part" "$file" || rm -f "$file.part"
    fi
  fi
fi
check "4K HEVC test file available ($file)" test -s "$file" || {
  echo "Pass a file: vela-ctl acceptance playback-check /path/to/4k-hevc10.mkv"
  finish; exit 1
}

# mpv logs its hwdec choice at info level; this script logs the drop counters once a second.
cat > "$dir/vela-stats.lua" <<'LUA'
local last = ""
local function snap()
  last = string.format("VELA-STATS vo-dropped=%s decoder-dropped=%s hwdec=%s frames=%s",
    tostring(mp.get_property_number("frame-drop-count")),
    tostring(mp.get_property_number("decoder-frame-drop-count")),
    tostring(mp.get_property("hwdec-current")),
    tostring(mp.get_property_number("estimated-frame-number")))
  mp.msg.info(last)
end
mp.add_periodic_timer(1, snap)
mp.register_event("end-file", snap)
LUA

log="$dir/mpv.log"
echo "Playing 600 frames full screen; the picture will cover the launcher for about 10 seconds."
mpv --no-config --hwdec=vaapi --vo=gpu-next --fs --frames=600 --msg-level=all=info \
  --script="$dir/vela-stats.lua" --log-file="$log" "$file" >/dev/null 2>&1
echo "mpv exit status: $?"
hwdec_used() { grep -q 'Using hardware decoding (vaapi)' "$log" || { echo "mpv did not report 'Using hardware decoding (vaapi)'"; grep -iE 'hwdec|vaapi|libva|error' "$log" | tail -n 3; return 1; }; }
check "mpv uses hardware decoding (vaapi)" hwdec_used
no_drops() {
  local s
  s=$(grep 'VELA-STATS' "$log" | tail -n 1)
  [ -n "$s" ] || { echo "no frame statistics in the mpv log"; return 1; }
  echo "$s"
  case "$s" in *"vo-dropped=0 decoder-dropped=0"*) ;; *) echo "dropped frames: $s"; return 1 ;; esac
}
check "mpv dropped no frames" no_drops
check "mpv exited after the clip" bash -c '! pgrep -x mpv >/dev/null'
sleep 2
home_focused() {
  swaymsg -t get_tree -r | jq -e '[recurse(.nodes[]?, .floating_nodes[]?) | select(.focused == true and ((.app_id // "") | test("^(chrome-127\\.0\\.0\\.1|chromium)")))] | length > 0' >/dev/null \
    || { echo "the launcher is not the focused window after mpv ended"; return 1; }
}
check "Vela home is back after the clip ends" home_focused
confirm "home screen visible after playback" "Is the Vela home screen on the TV again, with no black or frozen picture?"

# --- Jellyfin through Vela: resume position and progress reporting ------------------------------------
# The daemon's own path (POST /api/play), on the first Continue watching card.
jf_progress() { api_get /api/home | jq -r --arg k "$1" '[.rows[] | select(.id == "continue") | .items[] | select(.key == $k) | .progress // 0][0] // "gone"'; }
player_focused() {
  swaymsg -t get_tree -r | jq -e '[recurse(.nodes[]?, .floating_nodes[]?) | select(.focused == true and .app_id == "mpv")] | length > 0' >/dev/null
}
wait_until() { # wait_until <seconds> <cmd...>
  local end=$((SECONDS + $1)); shift
  until "$@"; do [ "$SECONDS" -lt "$end" ] || return 1; sleep 1; done
}
card=$(api_get /api/home 2>/dev/null | jq -c '[.rows[] | select(.id == "continue") | .items[]][0] // empty' 2>/dev/null)
if [ -z "$card" ]; then
  warn "Jellyfin resume through Vela" "no Continue watching row (media provider not jellyfin, or nothing half-watched). Start a 4K title in Jellyfin, stop it midway, and run this again"
else
  key=$(jq -r .key <<<"$card")
  title=$(jq -r .title <<<"$card")
  before=$(jf_progress "$key")
  left=$(jq -r '.remainingMin // "?"' <<<"$card")
  info "playing '$title' through Vela ($key), $(jq -r '(.progress // 0) * 100 | floor' <<<"$card")% watched, $left min left"
  if check "daemon starts $title" api_post /api/play "{\"key\":\"$key\"}"; then
    check "mpv is on screen" wait_until 30 player_focused
    sleep 15
    confirm "Jellyfin title resumed at the right spot" "Did '$title' pick up where it was left ($left min left), not from the start?"
    sleep 10
    # Back on the remote is Esc; mpv quits and Vela reports the position to Jellyfin.
    wtype -k Escape 2>/dev/null || echo "Press Back on the remote now."
    check "Vela home is back after Back" wait_until 30 home_focused
    sleep 3
    after=$(jf_progress "$key")
    moved() { echo "progress before $before, after $after"; [ "$after" != gone ] && awk -v a="$after" -v b="$before" 'BEGIN { exit !(a > b) }'; }
    check "Jellyfin progress moved forward ($title)" moved
  fi
fi

# --- YouTube TV ------------------------------------------------------------------------------------
manifest=/usr/share/vela/apps/youtube.json
if [ -r "$manifest" ] && command -v flatpak >/dev/null 2>&1 && flatpak info --system com.google.Chrome >/dev/null 2>&1; then
  mapfile -t args < <(jq -r --arg url "$YT_URL" '.args[] | if startswith("--app=") then "--app=" + $url else . end' "$manifest")
  echo "Opening YouTube TV with a 4K test video (sign in first if it asks; you may need to start the video)."
  flatpak run com.google.Chrome "${args[@]}" >"$dir/youtube-chrome.log" 2>&1 &
  sleep 25
  echo "Open Stats for nerds (on the YouTube TV player: Options, then Stats for nerds) and let it play ~30 s."
  sleep 15
  screenshot playback-youtube >/dev/null 2>&1 || warn "screenshot" "grim could not capture the screen"
  confirm "YouTube shows 2160p, VP9 or AV1, no dropped frames" "Stats for nerds: resolution 2160p, codec VP9 or AV1, dropped frames 0 (or tiny)?"
  flatpak kill com.google.Chrome >/dev/null 2>&1
  vela-ctl home >/dev/null 2>&1
else
  check "Chrome Flatpak installed for the YouTube check" false
  echo "FAIL YouTube TV check skipped: com.google.Chrome is not installed yet (see hw-check)."
fi
screenshot playback-check-screen >/dev/null 2>&1 || warn "screenshot" "grim could not capture the screen"
finish
