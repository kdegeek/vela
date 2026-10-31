# First-wave TV and companion UI review

Date: 2026-10-31. Branch: `t3/explore-agentic-tv-pivot`.
Baseline requested: `4b711104d82f5b5cd8952d8d20390dddd9597b0d`.
Scope: design tokens/components, `TvShell`, `HomeActivity`, `AskActivity`,
`TitleDialog`, phone companion HTML, and new UI-focused tests only.

## Visual evidence and device blocker

**No baseline or after-change screenshot was captured. The modified UI has not
been visually verified.** The existing debug APK was not launched by this worker.
The baseline APK cannot establish evidence for this source change.

Device operations were restricted to the authorized emulator, host `local`,
device `vela-gtv-4k`. `device_open` failed, then `device_list` refreshed discovery,
then the one allowed `device_open` retry failed with the same result:

```text
Device vela-gtv-4k failed to boot: The simulator or emulator could not start.
Failed to allocate an emulator port for vela-gtv-4k
[android-utils] Failed to run `avdmanager list avd`:
... avdmanager: line 173: test: : integer expression expected
The operation couldn’t be completed. Unable to locate a Java Runtime.
```

Discovery still listed the emulator as Android, nonphysical, not booted, and the
host as ready with the avdmanager warning. The device path was stopped after the
retry. No session command was returned, so there were no launch/input/screenshot
commands, alternate UI-control routes, or physical-device operations. No JDK,
hub, emulator infrastructure, or host configuration was changed.

## Code-inspection findings and changes

| Finding in baseline source | Change in owned files |
| --- | --- |
| Home requested first-card focus after asynchronous row loading, overriding navigation focus. | Removed the asynchronous focus request. Initial focus belongs to Home navigation. Lazy content and navigation use Compose focus restoration; destination scroll/search state has a saveable state scope. Back scrolls the navigation to Home before requesting its focus. |
| The fixed 150 dp hero consumed much of the remaining surface below the shell header, leaving poster rows difficult to see. | Hero/pairing are items in the vertical content scroller. Each full row can scroll into view. Removed the forced top-of-row animation on every horizontal focus change. |
| Four hard-coded columns could overflow at smaller surface sizes. | Apps/My list use an adaptive lazy grid sized for the actual card style, with focus overhang and safe-area space. |
| Artwork failure left a blank tile, progress could receive NaN, card names were inconsistent with/without artwork. | Text fallback remains until artwork succeeds; titles always appear below cards; one accessible card name includes its subtitle; finite progress is clamped and announced. |
| Title actions shared a narrow AlertDialog button row, episodes waited for the entire request, unsupported metadata had no explanation. | Bounded custom Dialog, wrapping Back/Save header, scrollable keyed body, distinct loading/error/unsupported/episode/playable states. Playback remains disabled until valid metadata loads. Save failures remain in the sheet. |
| New media APIs support seasons, pages and missing/played state. | Title sheet uses season filters, 50-item episode pages, explicit load-more/retry, missing-item gating and resumePositionMs. Changing a series/filter cancels pending appends and guards stale completions. |
| Speech results immediately executed, errors could leave a busy recognizer, and the field lacked a visible label. | Speech fills a reviewable draft. Explicit Ask sends it. Permission/network/no-match errors leave typing and Back; canceled callbacks are ignored; the draft survives recreation; layout scrolls around IME and long content. Missing AI setup is explained. |
| Search offered no retry or missing-library recovery, settings failures were silently swallowed. | Search can retry the same query, explains a missing library and links to Settings. Agent handoff shows Activity. House has explicit setup/retry actions. Settings wraps controls and displays failed launches/writes. |
| Failed owner actions only had Dismiss. | Launch/playback actions have explicit retry. Home-service/agent actions have no blind retry that might repeat an already-applied effect. |
| Phone retained enabled controls and stale approvals after disconnect; polling could re-enable actions mid-request. | Connection-gated controls, explicit reconnect, in-flight locks, single remote request, stale-prompt ID preservation, and clear remote-ownership feedback. No new API routes or server contracts. |
| Phone lacked a visible composer label and convenient navigation, activity timestamps were displaced by outcomes. | Visible label, section links, keyboard-scrollable logs, empty-state copy, persistent timestamps plus outcome, inline saving/loading feedback, full-width D-pad, 44 px checkbox labels, forced-color borders. Unsaved text/connection edits survive polling and recovery. Pairing fragment removal is independent of localStorage availability. |

Theme palettes remain semantic. Artwork is suppressed by high contrast and focus
scale/backdrop changes respect reduced motion. Phone CSS follows the existing
intrinsic grid, logical properties, OKLCH/light-dark tokens, keyboard outlines,
hover capability query and safe-area padding. No new animations or dependencies.

## Verification performed

- `node --test android/app/src/test/ui/phone.test.cjs`: **12 tests passed**.
  Runs the actual embedded companion script with in-memory DOM/HTTP boundaries:
  initial connection gating, disconnect/reconnect and retained drafts, unsaved
  settings preservation, active-agent remote exclusion, approval ID/duplicate
  protection across polls, duplicate settings submission, failed Ask recovery,
  malformed layout feedback, untrusted text rendering without forced scrolling,
  expired-pairing recovery, fragment cleanup when storage is unavailable, and
  serialization of remote requests across polling.
- `UiPresentationTest`: **3 JUnit tests passed** using the cached Kotlin 2.4.21
  compiler, JDK 21, and JUnit 4.13.2 directly in a temporary directory. Covers
  finite/clamped progress, stale loading/error metadata, missing titles, supported
  playback types, and series/container gating. No Gradle tasks were run.
- `git diff --check`: passed for the shared diff at verification time.

These are logic/regression checks. They do not verify Compose compilation,
D-pad focus behavior, accessibility ordering, screenshots, live services,
credentials, actual browser CSS rendering or media playback. No fixtures are
presented as live content. Whole-project Gradle/TypeScript checks belong to the
coordinator after peer integration.

## Exact integration dependencies

No external module edits are required by the base design-system/phone changes.
No new Gradle, npm, manifest, or server dependency is introduced.

The title sheet intentionally uses the media worker's additions now present in
this shared checkout. Retain these APIs in the combined result:

- `JellyfinClient.seasons(seriesId): List<JellyfinItem>`.
- `JellyfinClient.episodesPage(seriesId, startIndex = 0, limit = 50,
  seasonId = selectedSeason): JellyfinPage`, exposing `items` and `nextStartIndex`.
- `JellyfinItem.missing`, `playable`, `resumePositionMs`, `seasonNumber`, and
  `episodeNumber` (plus existing id/name/type).

The title sheet uses existing settings/watchlist methods and `TileAction` types;
`HomeData` does not need changes. `VelaButton` adds an optional `enabled` argument
before its trailing click lambda; existing trailing-lambda call sites remain
source-compatible. `TitleDialog` adds an `opening` argument and its sole shell
call is updated in this worker's ownership.

The companion continues using only existing `/api/state`, `/api/ask`, `/api/stop`,
`/api/history/clear`, `/api/answer`, `/api/remote`, `/api/settings`,
`/api/connections/test`, and `/api/layout` routes. If peer modules add new features,
their phone fields are separate integration work; this worker did not invent
protocol fields for those features.

## Remaining integration and acceptance work: 8 items

All eight are coordinator-resolvable with the restored emulator route and normal
combined checks. No new product decision is required for this UI scope. Live
service examples require approved test data/accounts; physical devices and
credentials retain the main task's approval gates.

1. **Combined checks:** with peers quiescent, run `./gradlew :app:testDebugUnitTest
   :app:assembleDebug` in `android`, then the coordinator's TypeScript/workspace
   checks. Verify the media API dependencies above. The Node UI suite is a separate
   command and is not automatically discovered by Gradle.
2. **Restore the device-tool route:** resolve the reported JDK/port-allocation
   failure in the device host's environment, then use `device_open` and its returned
   command. Install only the newly built APK. Do not bypass the device tool with
   adb input or claim the old APK verifies these changes.
3. **Night-theme traversal:** screenshot Home, Search, Apps, My list, House,
   Activity and Settings using `device_screenshot`. D-pad through each direction;
   verify first focus, Up to navigation, horizontal row return, title dismissal,
   scroll restoration, repeated app return, and Back-to-Home when Home navigation
   has scrolled offscreen. Save evidence as `ui-night-<destination>.png`.
4. **Day-theme traversal:** repeat all destinations and title/Ask sheets in Day.
   Verify legible artwork gradients and all focused/selected states. Save
   `ui-day-<destination>.png`. Theme switching alone is not evidence for both modes.
5. **Title-sheet recovery:** use approved test library data for long titles,
   absent/broken art, missing/unknown types, empty series, multiple seasons, over
   100 episodes, resume/played state, save/remove, initial/page failures and retry.
   Change a season during a page request, then close/reopen. Verify no stale append,
   reachable episode actions, safe focus after list removal or the final page,
   bounded long descriptions, and restoration to the originating card.
6. **Ask and search recovery:** missing AI/library setup, blank query, repeat
   search, no results, library failure alongside working app results, keyboard
   Send/Search, microphone denied/unavailable/no match/network failure, canceled
   speech callbacks, reviewed speech draft, Back and activity handoff. Verify the
   keyboard never hides the only escape/action.
7. **Accessibility/sizing:** increased Android font scale, TalkBack navigation
   and card/progress names, smallest granted app surface, long labels, high contrast
   and reduced motion. Verify focus outlines are visible with no clipped card
   overhang, wrapping settings controls and no inaccessible dialog footer.
   Overscan/hardware behavior is later physical-device work, not emulator proof.
8. **Real companion browser:** light/dark at 320/390/768 CSS px and 200% zoom;
   keyboard-only focus/section navigation; connect, disconnect and expired token;
   unsaved edits/drafts, network timeout, new approval during an in-flight answer,
   active-agent remote exclusion, JSON errors and all loading messages. Record
   actual browser screenshots; in-memory Node tests do not render CSS.

## Official API references checked

Installed catalog: Compose BOM `2026.09.00`, resolved Compose UI `1.12.1`, Kotlin
`2.4.21`, Coil `3.6.3`. No versions were changed.

- [Compose focus restoration](https://developer.android.com/develop/ui/compose/touch-input/focus/focus-restoration): lazy focus groups and `focusRestorer`.
- [Compose Dialog](https://developer.android.com/develop/ui/compose/components/dialog): custom content for multi-control sheets, explicit container and bounded dimensions.
- [Compose semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics): headings, selected state, control roles and polite status announcements.
