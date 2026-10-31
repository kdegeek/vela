# Entertainment-first Home and checked Undo

2026-10-31. [Interactive capture gallery](index.html). Eight unedited native
captures from the local Android 14 Google TV emulator (`emulator-5554`),
1920 × 1080 pixels. The physical Shield was not contacted.

## Build provenance

The debug APK was assembled from the Home/Undo integration on base commit
`20502607a7667bfc012168f5819d84a1b4941ea4`. This evidence and the integrated
sources are delivered together. Only indentation changed in production sources
after the verified build; no behavior changed between building and capturing.
The [manifest](manifest.json) records APK and PNG hashes. The source APK was
installed through the thread's emulator-bound `agent-device` command.

`testDebugUnitTest lintDebug lintFirmware assembleDebug assembleFirmware` passed:
**498 tests in 72 suites**, zero failures/errors/skips; both lint variants have
zero errors and 13 existing warnings. Both APKs assemble. The new suites include
20 storage-history, eight prepared Undo and 14 media-header/default-order cases.
The existing row-edit/presentation tests also pass.

## Recorded interactions

1. The installed app retained the emulator's two-row saved custom layout. The
   coordinator privately backed up its exact layout and connection settings.
2. The actual TV Arrange home → Restore default layout confirmation restored
   `resume, apps, latest, watchlist`. Paired authenticated read-back verified
   the persisted layout. Empty unconnected rows were omitted from the display.
3. The native Home Undo button restored the **exact original layout JSON**.
   Reapplying the default and pressing Keep layout hid the notice while leaving
   Undo available beside Arrange home. No tile action ran.
4. Temporary fixture-only Jellyfin settings loaded two Continue watching titles
   and paginated catalog metadata. D-pad movement from the first title to an
   episode changed the header; moving down to Apps retained that episode header.
   The app was not activated. Night and Day were selected through native Settings.
5. A second native row move generated the final notice capture. Its native Undo
   restored the entertainment-first default order, verified directly in private
   app storage. This consumed that receipt and left the emulator on default Home.
6. The exact original settings were restored and compared with the persisted file
   without printing credentials. The private backups were deleted. The fixture
   listener stopped, and only the run's `18765` forward / `18096` reverse mappings
   were removed. No model call, real service, purchase or physical-device action
   was performed.

## Fixture and capture corrections

The first populated capture exposed 401 image responses. Vela uses plain Coil
URLs; Jellyfin's [item-image GET/HEAD controller](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/ImageController.cs)
does not require authentication. The fixture incorrectly required a token on
those routes. A new test reproduced 401 before correction. Only recognized image
GET/HEAD routes are now anonymous; metadata, effects and playback remain guarded.
**20 fixture HTTP tests pass**. Fresh app observations showed image responses of
200 and zero applied actions. This corrected the fixture, not production auth.

The default screenshot scope also omitted the shell on the notice capture,
although accessibility reported the correct global positions. Back-to-back
captures without any UI action showed that `screenshot --fullscreen` preserves
the shell and matches those positions. The final notice uses that full-frame
capture. Other files were visually inspected for complete shell/content; no
cropping, annotations or redaction were applied.

## Limits

The color blocks are original synthetic artwork, not a real household library.
These checks do not establish playback, model responses, MCP transport execution
of the new tool, accessibility timing, enlarged text or physical Shield behavior.
Undo is one step and session-only; restart retains the layout but clears history.
The broader [six acceptance groups](../ui-integration-2026-10-31.md#remaining-acceptance-six-groups)
still apply. No visual sign-off or firmware qualification is claimed.
