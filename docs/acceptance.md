# Checking the box on real hardware

Vela was built without access to a Mac mini, so these five scripts are how we find out what
works. Run them after the install ([install.md](install.md)) and the remote setup
([remote.md](remote.md)), then send the report files back.

Every check prints one line: `PASS name`, `FAIL name: what the tool said`, or `WARN name: ...`
for optional things such as Wi-Fi. Nothing is changed on the box, except that the checks press
keys, play video and turn the TV on and off.

## How to run them

Log in to the box over SSH (`ssh -t root@<box address>`) with the TV on and Vela's home screen
showing, then run each script by name:

```
vela-ctl acceptance hw-check
```

The scripts run as the `vela` user inside the kiosk session, even when you start them as root.
Use `ssh -t` (a terminal is needed for the questions; without one they are skipped with a
`WARN`). They ask `y/n` questions where only you can see the result (the TV, the picture, the sound).
Each script ends with `Report bundle: /var/home/vela/vela-report-<date>-<time>.tar.gz`. A bundle
holds the output of every script run so far, so the last one you make is the one to send.

## Order

1. **`hw-check`** (1 minute). Is the hardware seen at all? Run it first; fix what fails before
   going on.
2. **`perf-check`** (about 1 minute). Does the home screen hold 60 fps at 4K? Don't touch the
   remote while it runs; it moves the focus by itself for 30 seconds.
3. **`playback-check [file]`** (about 3 minutes). Hardware video decoding in mpv, then YouTube at
   2160p. It needs a 4K HEVC 10-bit file. With no argument it makes a 20 second clip with ffmpeg
   if ffmpeg is installed; otherwise make one on another computer and copy it over:

   ```
   ffmpeg -f lavfi -i testsrc2=s=3840x2160:r=60 -t 20 -c:v libx265 -pix_fmt yuv420p10le /var/tmp/vela-4k-hevc10.mkv
   vela-ctl acceptance playback-check /var/tmp/vela-4k-hevc10.mkv
   ```

   Sign in to YouTube once first (install.md step 9), or the test video won't play.
4. **`tv-check`** (about 2 minutes per TV). Turns the TV off, waits 10 seconds, turns it on, and
   changes the volume. You say whether each step happened. Pair the TV in Home Assistant first
   (install.md step 10).
5. **`ir-check`** (about 1 minute). Sends a test code on each infrared transmitter: the USB IR
   dongle (`/dev/lirc*`) and the headphone-jack blaster. A phone camera shows an IR blaster's light
   flashing.

## What a failure most likely means

### hw-check

| Failure | Most likely cause |
|---|---|
| `kernel module t2bce_core loaded` | The box did not boot the t2linux kernel, or the T2 chip's driver failed. Send the bundle; `t2bce_vhci` (keyboard bridge) is reported on the next line. |
| `running kernel matches the image's VELA_KERNEL` | An older deployment is booted. Check `bootc status`, then reboot. |
| `internal NVMe present` | The T2 storage driver did not load, so this install is running from somewhere else. |
| `output is 3840x2160 at 59.9 Hz or more` | The HDMI cable or port, or the TV input, is not HDMI 2.0 at 4K60. Use a certified High Speed cable, the Mac mini's HDMI port (not USB-C with an adapter), and the TV's HDMI input that supports 4K60 ("HDMI UHD Color", "Input Signal Plus" or "Enhanced format" in the TV's settings). The output line shows what the TV did get. |
| `vainfo lists VAProfileHEVCMain10` | No hardware HEVC. `intel-media-driver` is missing or the GPU is not using `i915`. Look at `vainfo.txt` in the bundle. |
| `vainfo lists VAProfileVP9Profile2` | Same as above; without this YouTube's 4K HDR streams fall back to software decoding. |
| `Flirc receiver present` | The Flirc is not plugged in or is not seen on USB: replug it. |
| `Ethernet link up` | The cable or router port, or the T2 network driver. |
| `HDMI audio sink exists` | The TV is off or not connected, or PipeWire did not start. See `audio-sinks.txt`. |
| `headphone-jack sink exists` | The audio codec is not set up by `t2linux-audio`. IR blasting through the jack will not work. |
| `Wi-Fi interface` (a warning) | The firmware in `/var/lib/vela/firmware` is missing. Wi-Fi is optional. |
| `vela-daemon answers`, `units active`, `kiosk window on workspace home` | The session did not start properly. Send the bundle; the user journal inside it shows why. |
| `graphical-session.target active`, `xdg-desktop-portal answers` | The session did not hand its environment to the user manager. Apps from Flatpak will not start. |
| `Flatpak ... installed` (a warning) | The first-boot download is still running or failed (no network). Look at `journalctl -u vela-flatpaks`. |

### perf-check

| Failure | Most likely cause |
|---|---|
| `perf report received` | The launcher is not on screen (an app has focus), or the browser crashed. Run `vela-ctl home` and try again. |
| `frames measured at width 3840` | Chromium is not rendering at the full 4K. The window is smaller or the display is on a lower mode; check `hw-check` first. |
| `p95 frame time` / `dropped frames` | The GPU can't keep 60 fps with the full effects. The measured effects mode is printed in the output: in `auto` mode the run measures **full** effects. Set `effects = "lite"` in `/etc/vela/vela.toml`, restart, run it again, and send both results. |

### playback-check

| Failure | Most likely cause |
|---|---|
| `mpv uses hardware decoding (vaapi)` | The VA-API driver doesn't work (see the `vainfo` failures above), or the file's codec isn't supported in hardware. |
| `mpv dropped no frames` | Decoding or the display path is too slow. Send the bundle; `mpv.log` has the counters. |
| `Vela home is back after the clip ends` | mpv stayed open, or the launcher did not get the focus back. Send the bundle. |
| YouTube answers `n` | Not at 2160p: the account isn't signed in, the TV advertises less than 4K, or Chrome's hardware decode is off. Note which of resolution, codec, and dropped frames were wrong. |

### tv-check and ir-check

| Failure | Most likely cause |
|---|---|
| `a TV device is configured` | No `kind = "tv"` entry in `/etc/vela/vela.toml`, or the daemon was not restarted. |
| `power_off request` / volume requests fail | The TV is not paired in Home Assistant yet, or the token is missing from `secrets.json` (install.md step 10). |
| TV off or on answered `n` | Home Assistant took the command but the TV ignored it. For power on, add `powerOnFallback`, which sends the IR power code. |
| `INFO power on used the IR fallback` | Information only: the TV could not be woken over the network. |
| `at least one IR transmitter is present` | No `/dev/lirc*` device and no headphone-jack sink. Plug in the IR blaster. |
| `ir-ctl sends on /dev/lirc0` fails | The transmitter is a receive-only device, or is not an IR blaster. |
| `pw-play sends on the headphone-jack sink` / `jack blaster transmitted` | The blaster isn't in the headphone jack, or the wrong sink was picked (the line above it names it). Set `audioJackSink` under `[ir]` in `vela.toml` to the right node from `wpctl status`. If the TV made a sound, the burst went to HDMI: send the bundle. |
| `WARN headphone-jack sink` | PipeWire shows no analog or headphone output. See `wpctl-status.txt` in the bundle. |
| `daemon sends <id>/<command>` | The device entry in `vela.toml` is wrong (file, button, or transmitter name). |

## What to send back

Send the **newest** `vela-report-*.tar.gz` from the last script you ran. Copy it to your
computer with:

```
scp root@<box address>:/var/home/vela/vela-report-*.tar.gz .
```

It holds each script's output, this boot's system and user journal (the last 3,000 lines each),
the Sway outputs and window tree, `vainfo`, `/usr/lib/os-release`, and screenshots. API keys and
tokens in the logs are replaced with `[redacted]`, and `secrets.json` is never included. Screenshots are not redacted: look at them before you send the bundle. Also send
your answers to the y/n questions if you noticed anything the script did not ask about (a flicker,
a sound glitch, a slow screen).
