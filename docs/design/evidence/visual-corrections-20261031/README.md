# Rendered UI corrections

2026-10-31. Coordinator corrections to the actual `2afc4ab` renders after the
owner requested reviewable UI. [Compare original and corrected captures](index.html).
The first gallery and independent rendered review remain in
[review-2afc4ab](../review-2afc4ab/README.md).

## Four addressed findings

1. **Clipped Settings tab.** Tabs now use 8 dp horizontal padding while other
   buttons retain their spacing. All seven labels fit on the baseline surface.
   Selection from the profile chip or another page also scrolls its tab into
   view, without requesting focus or changing controller admission.
2. **Duplicate field outlines.** A focused field has one outset ring, a focused
   fill and cursor. Material's second inner outline is transparent only while
   focused. Error feedback remains textual, with a danger border when unfocused.
   High contrast retains its 4 dp ring. The design-language field rule is amended
   accordingly. Field focus targets, editability, masking, length limits and
   keyboard callbacks retain their existing behavior.
3. **Internal profile IDs in product copy.** Profile list descriptions and memory
   editor headings use owner-facing names/preferences. Stored IDs and all
   management arguments remain unchanged.
4. **Empty management status slots.** The shared feedback item exists only with
   a message; profile, memory, routine and external-control owner items exist
   only with an owner. Dialogs remain composed at page level. Actual content
   keeps its 24 dp spacing. Idle Profiles gains 48 dp of useful space, and an
   idle editor gains 24 dp.

## Executed evidence

The final code passed:

```sh
env JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew --no-daemon \
  testDebugUnitTest lintDebug lintFirmware assembleDebug assembleFirmware
```

456 JVM tests in 68 suites, zero failures/errors/skips. Both lint variants have
zero errors and 13 existing warnings. Both APKs assemble. The unchanged palette
tests already cover supporting ink on the focused fill; focused placeholder and
unavailable-field text now use that role. Computed placeholder contrast is
6.24:1 Night, 6.25:1 Day, 11.71:1 Night HC and 11.79:1 Day HC.

The coordinator installed the final debug APK over the existing Google TV
Android 14 emulator app and captured **11 unchanged 1920 × 1080 PNGs**: Home,
Profiles, an unsaved profile editor, the composer and appearance Settings in
both themes, plus visible completion feedback after an explicit read-only
Refresh profiles action. Both profile action buttons now fit in the idle
viewport; the selected Settings label is fully readable; the focused composer
has one outline. The unsaved editor was dismissed, and no model request was sent.

All launch/input/capture operations used the exact session command returned by
`device_open` or `device_screenshot`. A stale reference was refused; the capture
was redone after a fresh full snapshot. The final files exclude that intermediate
capture. The emulator was returned to Night Home. The physical Shield remains
paused and untouched.

Exact APK bytes, hashes and capture hashes are in [manifest.json](manifest.json).
These artifacts were built from the coordinator's correction to base commit
`2afc4ab`; the containing Git commit records the final source and evidence. The
debug APK SHA-256 is
`8d8014afd55030da4e26b3e237e4e13573c29727b12fb4bfb03e9b44593ceb27`.

## Limits

This addresses the four established visual issues and supplies a concrete review
surface. It does not establish acceptance of populated media/detail, playback or
Now Playing, TV approvals, remaining management states, larger text, high
contrast rendering, IME behavior or complete D-pad/accessibility timing. Owner
busy/Stop was source-checked but was not rendered in this pass. The physical
Shield pause is unchanged. Broader acceptance remains in
[integration evidence](../ui-integration-2026-10-31.md).
