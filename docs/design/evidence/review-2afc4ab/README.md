# Current build visual review

Captured 2026-10-31 from the actual debug APK built from `2afc4ab`, installed over
the existing Vela Google TV emulator application. APK SHA-256:
`dbc51dd22c1f327335809b68b167c2d0885f1a920f523c30c8b3c40d609533a7`.

[Open the screen picker](index.html). The coordinator also presented this gallery
inline in the T3 thread, using `html_preview` then `html_render`. Preview returned
no console messages. This is a screenshot viewer, not a browser recreation of the
native application.

## Actual captures

- Home, Apps and appearance Settings in Night and Day.
- Profiles, an unsaved profile editor and the request composer in Day.
- Two earlier native Safari phone captures, linked from the parent evidence
  directory, using only public synthetic fixture data.

All nine TV PNGs are unchanged 1920 × 1080 captures from Android 14 emulator
`vela-gtv-4k`. The snapshot command identified the expected current screens. Day
was selected through the real app and its checked state read back. D-pad Down
focused Profiles, Routines, External control, Now Playing, permission controls and
Android settings, scrolling the native list to appearance controls. No complete
D-pad traversal or controller timing qualification is claimed.

The initial selector-based scroll command returned `scroll down reached the end
of the scrollable content after 0 passes without text="Theme" becoming visible`.
The prescribed fresh snapshot and screenshot showed the top of Settings; actual
D-pad navigation then reached the controls. No raw ADB input or screenshot
fallback was used. All app launch, input and captures used the exact command
returned by `device_open`, or `device_screenshot`.

The emulator's existing Household and pinned action were retained. No profile was
saved and no model request was submitted. Settings' pairing QR was excluded from
these capture selections. The physical Shield was never opened or controlled.

## Disposition

The four established findings, including the independent spacing finding, were
subsequently corrected and recaptured in both themes. See
[correction evidence and comparison](../visual-corrections-20261031/README.md).
The list below records the original reviewed state.

These captures replace the previous absence of current native visual evidence.
They establish a first review surface, not design acceptance. Already visible:

1. The Settings tab is clipped to a fragment beside the fixed profile/agent
   controls, including while Settings is selected.
2. The focused request field has duplicate focus outlines.
3. The profile list exposes its short internal ID in ordinary product copy.

The coordinator owns correction and recapture. Populated library/title detail,
native playback/Now Playing, TV approvals, remaining management states, high
contrast, large text, IME behavior and complete D-pad/focus restoration still need
visual coverage. The owner is not asked to accept those unshown states.

The earlier `tv-day.png` and `tv-night.png` contact sheets predate the redesign and
were deliberately excluded from this current-build gallery.
