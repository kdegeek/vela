# Physical Shield Tube UI verification

On 2026-11-01 UTC the owner resumed work on the physical **2019 Shield TV Tube
(`sif`), stock Android 11/API 30, ARMv7**. The owner then woke the device with its
remote. The bound device route opened Vela successfully after the earlier asleep
launch timeouts. No firmware operation was performed.

The older installed debug APK was backed up, and both old/current APK signatures
verified with the same signing certificate before an in-place update. Tested APK:
53,344,160 bytes, SHA-256
`d342b62711ccb5eb821121433163d556e9ff74f883f6013e621827f268a46592`, matching the
[input-field correction build](../field-correction-20261031/README.md). No source
change or new build was needed for this verification.

## Actual observations

- Vela Home renders on the physical Tube. Native D-pad Down enters the app row;
  Right/Left move focus; Up returns to Home. A 16-Right/17-Left traversal reached
  YouTube at the last boundary and Apple TV at the first without wrapping or
  launching an app.
- Ask opens with the static label above the filled field and its independent
  focus outline. The stock Shield keyboard appears. The first Back dismisses the
  keyboard while retaining Ask; the next closes Ask. The synthetic draft
  `Shield input check` was entered, observed in the editable accessibility node,
  then cleared. Ask stayed disabled with AI disconnected. No model request or
  speech/microphone action was made.
- Home and Ask were captured and visually inspected in Night and Day. Night was
  restored. API 30 uses the tonal/pre-softened-artwork path; this does not establish
  live RenderEffect blur support.
- The original pairing, device-id and household files remain byte-identical.
  There was no original settings file; theme switching created one. Its final
  values match the original defaults: Night, standard motion, standard contrast,
  and system Home behavior. No provider credentials were added. Private backups
  and diagnostics remain outside Git.
- The task ended on **Vela Home in Night mode**, as requested. The app/session was
  deliberately left open. The existing display override was retained: physical
  surface reports 3840×2160, app capture is 1920×1080. This is not native-4K UI proof.

## Unmodified device captures

All PNGs are 1920×1080 captures from the bound physical device command. No mock
catalogue or fixture server was used. The older UI's live pairing QR was not
exported. [SHA256SUMS](SHA256SUMS) identifies the captures.

| Capture | Observation |
| --- | --- |
| [Final Night Home](home-final.png) | Home focused, first app visible, final leave-open state |
| [Day Home](home-day.png) | Day materials; the row retains its earlier horizontal scroll position |
| [Night Ask with native keyboard](ask-night.png) | Stock Shield IME over the focused input |
| [Night Ask draft](ask-night-draft.png) | Keyboard dismissed; draft and label visible; Ask disabled |
| [Day Ask](ask-day.png) | Empty corrected field and Day sheet; keyboard dismissed |

## Bounded frame sample

After resetting `dumpsys gfxinfo`, two native-remote batches dispatched 33
horizontal key presses (12.745 s and 13.912 s of batch work, with idle/inspection
time between). The final [raw frame summary](gfxinfo-traversal.txt) records:

- **423 rendered frames**, **20 janky (4.73%)**.
- Frame p50 **8 ms**, p90 **12 ms**, p95 **15 ms**, p99 **40 ms**.
- **10 frame-deadline misses**, one missed-vsync counter and 62 high-input-latency
  counter events. These counters are retained; command dispatch duration is not
  end-to-end input latency.

This is one warm, automated traversal on a debug APK at the existing 1080p
override. It is not a release benchmark, smoothness gate, soak, leak, 4K or media
qualification. The bounded current-process log sample contained no fatal-exception
marker; no crash was observed. Raw framestats/meminfo/logs and the earlier
intermediate diagnostic sample are retained locally under ignored
`firmware/shield/artifacts/physical-20261101/`.

Two automation issues were resolved without an app change: the field selector
initially saw only the IME tree, so Back and a fresh field reference were used;
the installed batch schema uses `command/input`, replacing the older skill's
`positionals/flags`. No raw ADB UI-input fallback was used.

## Remaining acceptance

This advances the Shield UI/input gate. It does not complete the broader queue:

1. API 30 accessibility-service control and Home interception, enlarged text,
   high contrast/reduced motion, notification/Now Playing permission flows,
   schedules/contention and longer performance/media testing remain unverified.
2. Populated phone editors and OS accessibility rendering remain separate work.
3. Real model/Jellyfin/Plex/Home Assistant acceptance needs configured services;
   no such credentials/endpoints were added during this pass.
4. Verified stock rollback acquisition, then separately approved firmware
   unlock/wipe/install/boot/OTA/recovery acceptance remain outstanding. The built
   development firmware was not installed on this stock device.
