# Rendered UI review — 2afc4ab

2026-10-31. Read-only visual audit of the nine TV PNGs in this directory and the
two phone PNGs linked by [index.html](index.html). Intent was checked against
[the design language](../../vela-design-language.md) and
[the component contract](../../vela-component-contract.md).

**One additional finding: P3 (low priority).** No additional high- or
medium-priority defect is established by these captures. The three issues already
assigned to the coordinator are excluded. This is not visual acceptance.

Coordinator disposition: the additional finding and the three original issues
are now addressed, with actual corrected renders and checks in
[rendered UI corrections](../visual-corrections-20261031/README.md). Original
review evidence below is retained for its reviewed build.

## 1. P3 — Empty status items consume space on idle management screens

**Observed evidence.** Coordinates below are pixels in the unchanged 1920 × 1080
TV images, measured from the top-left:

- [Profiles](tv-profiles-day.png): within `x=96–1255, y=255–496`, the page title's
  last painted row is `y=312`, while the action pills begin at `y=409`. A second
  oversized break appears within `x=96–1216, y=599–760`, between the final line of
  explanatory prose (`y=599–629`) and “Profiles” (`y=732–760`). The profile's
  Preferences / Explicit memory buttons start at `y=977` and are only partially
  visible before the content viewport ends at `y=1026`.
- [New-profile editor](tv-profile-editor-day.png): within
  `x=96–566, y=255–496`, the same title-to-action gap precedes Back. The subtitle
  field begins at `y=1002`, with its label below the visible viewport.

**Established cause.** These source files were compared byte-for-byte with
`2afc4ab` and match the captured commit:

- `android/app/src/main/java/app/vela/tv/launcher/ManagementPage.kt:75–77`
  unconditionally inserts the `management-feedback` lazy item, while
  `StatusFeedback()` at lines 51–56 emits nothing when its message is empty.
- `android/app/src/main/java/app/vela/tv/launcher/ProfileManagement.kt:61`
  unconditionally inserts `profile-owner`. Its `OwnerControlStatus()` content
  emits nothing without an owner
  (`launcher/ManagementPage.kt:131–141`).
- `android/app/src/main/java/app/vela/tv/design/Pages.kt:16–18` places 24 dp
  between lazy items. Each empty item therefore introduces an additional 24 dp
  gap even though it has no visible content. The profile list has two such slots;
  the editor has the shared feedback slot.

**Impact and design basis.** The idle list spends an extra 48 dp on absent status
content, and the editor spends 24 dp. This separates titles from their actions
and reduces immediately readable content on the baseline TV surface. This is a
concrete spacing artifact from empty list entries, not a request to make all
management screens denser. The partially visible bottom controls are normal
scroll-viewport clipping; these stills do not establish that they are unreachable.

**Bounded correction.** Create the feedback lazy item only when feedback exists.
Likewise, make owner-status presence available when constructing the profile
list, and omit its item while there is no status to render. Keep the confirmation
dialog composed at page level, retain stable keys for visible items, and preserve
the existing 24 dp spacing between actual content. No typography, global spacing,
management behavior or schema change is needed.

**Follow-up evidence needed.** The coordinator should recapture these two idle
screens at the same viewport, then check a visible busy/feedback state to ensure
its status still appears. The expected idle improvement follows from removing
the empty items; no corrected render or focus/restoration behavior was observed
in this review.

## Coverage and remaining limits

- Inspected all nine TV captures: Home, Apps and appearance Settings in Day and
  Night; Profiles, new-profile editor and Ask in Day. Also inspected
  [phone approval](../vela-phone-night-pending-1280.png) and
  [phone profile editor](../vela-phone-night-profile-clean-draft-1280.png).
  No additional actionable defect is proven in the other supplied images.
- The observed palettes, serif/sans hierarchy, agent tint, Settings selection
  checks and Night Settings focus treatment follow the stated design direction.
  Sparse Home content and the broad Apps grid are not themselves defects; the
  contract permits adaptive grids and the capture contains only four apps.
- Per [README.md](README.md), TV captures came from the Android 14 Google TV
  emulator running the debug APK built from `2afc4ab`. That provenance was read,
  not independently established by installing or rebuilding. The two phone
  images are earlier Safari captures using synthetic fixtures, each 1280 × 840
  pixels; they do not establish a current-APK phone session or narrow-phone layout.
- Unshown states remain unassessed: populated media/detail, playback/Now Playing,
  TV approvals, remaining management states, high contrast, larger text, IME and
  narrow phone layouts. Stills establish neither D-pad traversal/restoration nor
  activation, cancellation, disabled-control behavior or motion. No physical
  Shield acceptance is claimed.
- Checks performed: document/source inspection, all 11 supplied image views,
  PNG dimensions and pixel-row measurements for the cited TV regions, and the
  three source comparisons above. No builds, runtime tests, browser/device/GUI
  actions or Git mutations were performed. The only authored artifact is this
  review.
