# Correct the shared text field

The owner's screenshot exposed a visual defect missed in the earlier glass review.
It was reproduced on the local Android 14 TV emulator before editing.

`VelaTextField` still used `OutlinedTextField` with an attached floating label.
Its external focus ring enclosed the whole component, including the floating-label
space above the fill. The label sat across that fill with a dark cutout, while the
ring appeared to surround a second box. This was our combination of treatments,
not an input or model failure.

The component now uses a normal filled Material3 `TextField`, a static label above
it, and the shared ring around the input container only. The accessible label stays
in the editable subtree; the visual label adds no separate focus target. Unfocused
and error boundaries remain explicit. Search's action aligns with the field below
the label using Material3's own minimum height. Management spacing was tightened
slightly so the additional label height does not clip the profile's Autoplay row;
Save and Back remain outside the scrollable form.

The existing controlled String API, interaction source, caller focus requester,
password transformation/reveal, read-only and disabled behavior, length guard,
line bounds and keyboard actions remain intact. Material3 **1.4.0** is the installed
version: its String overload has no above-label positioning option. The separate
label avoids migrating state ownership or changing dependencies. A read-only peer
checked that API choice and the behavior invariants.

## Actual verification

[Interactive capture gallery](index.html). All seven PNGs are unmodified 1920 × 1080
captures from the local emulator through its bound device command.
The gallery uses CSS to crop the three Ask comparison views to the sheet; the
original full-frame PNGs remain unchanged.

| Capture | Observation |
| --- | --- |
| [Before, Night](before-night.png) | Reproduced floating-label/focus-frame mismatch on the previous build |
| [After, Night](after-night.png), [Day](after-day.png) | Label above the fill, continuous ring around the input, no floating cutout |
| [Entered draft](draft-night.png) | `Preview only` entered successfully; Ask enabled, then clearing restored its disabled state; no submission |
| [Search](search-night.png) | Filled field and action share the same lower edge and minimum height |
| [Profile Night](profile-night.png), [Day](profile-day.png) | Name, language controls, Autoplay and persistent actions fit; no profile saved |

A raw device snapshot with the draft exposed **one editable target**, with
`label = value = "Preview only"`. Its child accessibility node exposed
`"Your request"` and referenced that editable node as parent. This checks the label
and value relationship and sole editor target; it does not establish spoken
TalkBack behavior. Both initial focus and cursor rendering were observed.

The final combined command passed from `android/` with JDK 21:

```sh
env JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew --no-daemon \
  testDebugUnitTest lintDebug lintFirmware assembleDebug assembleFirmware
```

**512 JVM tests, zero failures/errors/skips; both APKs built; both lint variants
zero errors and 13 existing warnings.** `git diff --check` passes. No cosmetic
source-string tests were added. Existing phone, activation and backend logic did
not change.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Debug APK | 53,344,160 | `d342b62711ccb5eb821121433163d556e9ff74f883f6013e621827f268a46592` |
| Firmware unsigned APK | 40,353,268 | `b1237c0e1ca32efa8c6e3252aa0198d88397fee652f5e20efddf67b65b34db2f` |

The Before frame uses the prior `0940a61` APK recorded in the
[original glass evidence](../glass-native-20261031/README.md). All six After frames
were recaptured from the final debug APK above. [SHA256SUMS](SHA256SUMS) identifies
the PNGs. The earlier image files remain frozen; that gallery now uses these newer
Ask and profile frames.

The emulator briefly showed Google TV's setup dialog after an app update. Cancel
returned to its launcher; a normal Vela open then resumed the expected Home.
Fresh snapshots and an empty crash buffer confirmed the recovery before visual
verification continued. No setup, account or network configuration was changed.

No model request, profile write, fixture server, new permission, physical Shield
action or firmware flash occurred. The temporary draft was cleared, Ask dismissed,
and original Night/standard-contrast/standard-motion settings verified restored.
Full TalkBack, enlarged-text/IME combinations and API 30 hardware acceptance remain
part of the existing acceptance queue.
