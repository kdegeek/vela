# Vela component contract

Status: governing implementation contract, 2026-10-31. Baseline `0809edfb31d2fac467dc55e2b67fd93d9cf777df`
on `t3/explore-agentic-tv-pivot`. Visual rules: [vela-design-language.md](vela-design-language.md).
This document fixes the APIs, file ownership, order and acceptance for the native and phone work packages.
Signatures below are frozen: a worker who needs a change stops and reports it instead of improvising.

## 1. Invariants every package keeps

1. **No backend or schema change.** `HomeLayout`/`RowSpec`/`Source`/`TileAction`/`TileStyle`, phone API
   routes and bodies, MCP tools, agent tools, settings fields and storage formats are untouched.
   Restyling never requires a new action, source, tile style, route or setting.
2. **Effects stay on their owned paths.** Design components receive only plain `() -> Unit` / value
   callbacks. They never receive `VelaApp`, `Runner`, settings stores or suspend lambdas, and never launch
   coroutines that cause effects. In `launcher/**`, every effect goes through exactly one of:
   `perform(action)` → `HomeData.perform` → `Runner.performTile`; `app.runner.executeTool` (Now Playing);
   `app.runner.ask/stop/clear`; the `app.management` facade; Android `startActivity` for system settings;
   or `saveOwnerLayout` (§4.4). No new path.
3. **Approvals are never answered in Vela's TV UI.** No `app.runner.answer` call in `launcher/**` or
   `design/**`. TV surfaces render pending approvals read-only (`ApprovalNotice`). The overlay keeps
   `TYPE_ACCESSIBILITY_OVERLAY`, `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE` and the exact hint
   `"OK to allow · Back to cancel"`.
4. **No new dependencies, fonts, raster assets, `@Preview` tooling, screenshot libraries or manifest changes.**
   Available: Compose BOM 2026.09.00 (ui, foundation, material3), Coil 3.6.3, ZXing, Media3.
   `design/**` may wrap Material3 `OutlinedTextField`, `LinearProgressIndicator`, `MaterialTheme`/`ColorScheme`,
   `LocalContentColor` and `Surface`. In `launcher/**`, M3 `Text`, `Surface` and `HorizontalDivider` are allowed;
   M3 `Button`, `Card`, `AlertDialog`, `OutlinedTextField`, `Checkbox` and `Switch` are not (use Vela components).
5. **No Git mutations, no device, emulator, browser automation, infrastructure, credential or
   firmware actions** in any package. `firmware/**` belongs to another worker.

## 2. Ownership (strictly disjoint)

| Package | Writes (only) | Read-only notes |
| --- | --- | --- |
| **WP1 Native foundations** | `android/app/src/main/java/app/vela/tv/design/**`; `android/app/src/test/java/app/vela/tv/design/**`; `android/app/src/main/java/app/vela/tv/control/Overlay.kt` (visual only) | Must not edit `launcher/**`. Keeps every currently used design symbol compiling (§3.12). |
| **WP2 Native shell & adoption** | `android/app/src/main/java/app/vela/tv/launcher/**` **except** `HomeData.kt`, `ManagementForms.kt`, `ManagementPage.kt`, `ProfileManagement.kt`, `RoutineManagement.kt`; new tests prefixed `Shell`, `Tile`, or `Layout` under `android/app/src/test/java/app/vela/tv/launcher/**` (existing tests untouched) | Never edits `design/**`, `settings/`, `agent/`, `control/`, `layout/`, `web/`, `runtime/`, assets or Gradle files. |
| **WP2m Native management adoption** | Only `launcher/ManagementPage.kt`, `launcher/ProfileManagement.kt`, `launcher/RoutineManagement.kt`; new tests prefixed `ManagementDesign` | Keeps existing internal function signatures compatible. Existing management tests and `ManagementForms.kt` are read-only. |
| **WP3 Phone** | `android/app/src/main/assets/phone.html`; `android/app/src/test/ui/phone.test.cjs` | Script changes limited to §6.4. No server/API change. |

Nobody edits `vela-tv-system.md`, `ui-review.md`, `management-ui.md` or these two contract documents.
Each package reports in its final message and may add one new evidence note
`docs/design/evidence/wp1-foundations.md`, `wp2-shell.md`, `wp2m-management.md` or `wp3-phone.md` (only its own).

Coordinator fleet expansion: independent WP4 owns only native player visual adapters;
WP5 owns only new `dev/design-fixtures/**`. Their detailed task briefs govern those
additional scopes. WP1 and WP6's initial audit are complete. The coordinator owns
the press-eligibility correction in `design/ActivationGate.kt` and `design/Focus.kt`.

## 3. WP1 API (package `app.vela.tv.design`)

File names are binding; Compose-free files must have no `android.*` or `androidx.*` imports so their tests
compile with bare `kotlinc` + JUnit, as previous reviews had to.

### 3.1 `VelaPalette.kt` (Compose-free)

```kotlin
data class VelaPaletteValues(
    val canvas: Long, val surface: Long, val surfaceRaised: Long, val surfaceFocused: Long,
    val ink: Long, val inkMuted: Long, val inkSubtle: Long, val line: Long, val lineStrong: Long,
    val accent: Long, val onAccent: Long, val accentStrong: Long, val focusRing: Long,
    val agent: Long, val onAgent: Long, val agentContainer: Long,
    val caution: Long, val danger: Long, val onDanger: Long, val success: Long,
    val highContrast: Boolean,
)

object VelaPalettes {
    val Night: VelaPaletteValues            // values: design language §4.1, ARGB 0xFF______
    val Day: VelaPaletteValues
    val NightHighContrast: VelaPaletteValues
    val DayHighContrast: VelaPaletteValues
    fun select(appearance: String, highContrast: Boolean): VelaPaletteValues // "day" → Day*, else Night*
    fun overlay(highContrast: Boolean): VelaPaletteValues                    // always a Night palette
    const val QR_DARK: Long = 0xFF0E1024
    const val QR_LIGHT: Long = 0xFFF6F1E7
}

/** WCAG 2.x contrast of two opaque ARGB colours (alpha ignored). */
fun contrastRatio(foreground: Long, background: Long): Double
```

### 3.2 `VelaGlyph.kt` (Compose-free)

```kotlin
enum class VelaGlyph { Sail, Search, Apps, Bookmark, House, Activity, Settings, Play, Pause, Stop,
    Previous, Next, Check, Caution, Error, Mic, Ask, Link, Profile, Refresh }
```

### 3.3 `UiPresentation.kt` (Compose-free; existing functions kept unchanged)

```kotlin
enum class StatusTone { Neutral, Agent, Success, Caution, Danger }
enum class StatusKind(val tone: StatusTone) {
    Info(StatusTone.Neutral), Empty(StatusTone.Neutral), Loading(StatusTone.Neutral),
    Success(StatusTone.Success), Caution(StatusTone.Caution), Error(StatusTone.Danger),
}
sealed interface AgentIndicator {
    data object Idle : AgentIndicator
    data class Working(val step: String?) : AgentIndicator
    data class NeedsApproval(val question: String) : AgentIndicator
}
/** A pending question wins over running; blank strings count as absent. */
fun agentIndicator(running: Boolean, pendingQuestion: String?, latestStep: String?): AgentIndicator
fun taskTone(state: String): StatusTone        // running→Agent, complete→Success, failed→Danger, canceled→Neutral, other→Neutral
fun taskStateLabel(state: String): String      // "Running", "Complete", "Failed", "Canceled", else capitalised
fun approvalInstruction(remoteAnswers: Boolean): String
// true  → "Press OK to allow once, or Back to decline."
// false → "Answer on your paired phone."
fun statusGlyph(tone: StatusTone): VelaGlyph?  // Agent→Activity, Success→Check, Caution→Caution, Danger→Error, Neutral→null
```

### 3.4 `VelaTokens.kt`

```kotlin
@Immutable class VelaColors(/* Color fields with exactly the VelaPaletteValues names */, val highContrast: Boolean)
fun VelaPaletteValues.toColors(): VelaColors
val LocalVelaColors: ProvidableCompositionLocal<VelaColors>   // default Night

object VelaSpace {
    val Xxs = 4.dp; val Xs = 8.dp; val S = 12.dp; val M = 16.dp; val L = 24.dp; val Xl = 32.dp; val Xxl = 48.dp
    val SafeHorizontal = 48.dp; val SafeVertical = 27.dp
    val CardGap = 16.dp; val RowGap = 24.dp; val HeaderToArt = 12.dp; val ArtToLabel = 12.dp
}
object VelaSize {
    val MinTarget = 44.dp; val ListRow = 56.dp; val TopBar = 52.dp
    val Hero = 96.dp; val HeroCompact = 52.dp; val NowPlayingBar = 64.dp; val NowPlayingArt = 48.dp
    val CardLabel = 40.dp; val Progress = 4.dp
    val DialogWidth = 640.dp; val DialogHeight = 460.dp; val SheetWidth = 720.dp; val SheetHeight = 480.dp
    val ReadingWidth = 560.dp
    val Glyph = 20.dp; val GlyphList = 24.dp; val GlyphStatus = 32.dp
    val CardRing = 3.dp; val CardRingOffset = 3.dp; val ControlRing = 2.dp; val ControlRingOffset = 2.dp
    val HighContrastRing = 4.dp
}
object VelaShapes {
    val Card = RoundedCornerShape(12.dp); val Panel = RoundedCornerShape(20.dp)
    val Control = RoundedCornerShape(50); val Field = RoundedCornerShape(10.dp); val Thumb = RoundedCornerShape(8.dp)
}
object VelaMotion {
    const val FocusMs = 150; const val PressMs = 100; const val BackdropSettleMs = 300L; const val BackdropFadeMs = 400
    const val FocusScale = 1.05f; const val PressScale = 1.02f
}
@Immutable class VelaTypography(val display: TextStyle, val headline: TextStyle, val title: TextStyle,
    val section: TextStyle, val body: TextStyle, val meta: TextStyle, val label: TextStyle,
    val caption: TextStyle, val numeric: TextStyle)
val VelaType: VelaTypography                    // design language §5 values
object Vela {
    val colors: VelaColors @Composable @ReadOnlyComposable get
    val type: VelaTypography get
    val reducedMotion: Boolean @Composable @ReadOnlyComposable get
    val highContrast: Boolean @Composable @ReadOnlyComposable get
}
/** tween(durationMs, FastOutSlowInEasing), or snap() when reduced motion is on. */
@Composable fun <T> velaTween(durationMs: Int): FiniteAnimationSpec<T>
```

### 3.5 `VelaTheme.kt`

```kotlin
val LocalReducedMotion: ProvidableCompositionLocal<Boolean>   // existing name, kept
val LocalHighContrast: ProvidableCompositionLocal<Boolean>    // existing name, kept
@Composable fun VelaTheme(settings: VelaSettings, content: @Composable () -> Unit) // existing signature
```

Provides `LocalVelaColors` from `VelaPalettes.select(settings.appearance, settings.highContrast)`, both
locals, and `MaterialTheme` with a `ColorScheme` mapped from roles (primary=accent, onPrimary=onAccent,
background=canvas, onBackground=ink, surface=surface, onSurface=ink, surfaceVariant=surfaceRaised,
onSurfaceVariant=inkMuted, outline=lineStrong, outlineVariant=line, error=danger, onError=onDanger,
secondary=agent, onSecondary=onAgent, secondaryContainer=agentContainer), typography mapped per design
language §5 (so unmigrated `MaterialTheme.typography.*` callers adopt the new scale automatically) and
`Shapes(small=Field, medium=Card, large=Panel)`.

### 3.6 `Focus.kt`

```kotlin
@Immutable data class VelaInteractionState(val focused: Boolean, val pressed: Boolean, val selected: Boolean, val enabled: Boolean)
enum class FocusTreatment { Control, Card, Row }
// Control: ring 2 dp outset 2 dp, no scale. Card: scale FocusScale/PressScale + ring 3 dp outset 3 dp. Row: ring 3 dp outset 2 dp.

@Composable fun VelaFocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean? = null,          // null: not selectable, no selected semantics
    treatment: FocusTreatment = FocusTreatment.Control,
    shape: Shape = VelaShapes.Control,
    role: Role = Role.Button,
    onClickLabel: String? = null,
    onFocusChanged: (Boolean) -> Unit = {},
    content: @Composable BoxScope.(VelaInteractionState) -> Unit,
)

/** Draws an outset ring outside the bounds; never covers content. */
fun Modifier.velaFocusRing(visible: Boolean, shape: Shape, color: Color, width: Dp, offset: Dp): Modifier
```

Behaviour: always focusable (also when disabled); click and press ignored while `enabled == false`;
`semantics { disabled() }` when disabled, `selected` when non-null; no ripple (`indication = null`);
pressed state from the interaction source (D-pad centre/Enter emits press interactions); `modifier` is
applied before the focus target, so callers attach `Modifier.focusRequester(...)` through `modifier`.
High contrast uses `VelaSize.HighContrastRing`. Reduced motion removes scale.

### 3.7 `Buttons.kt` and `Glyphs.kt`

```kotlin
enum class ButtonVariant { Primary, Secondary, Agent, Danger, Quiet }

@Composable fun VelaButton(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    variant: ButtonVariant = ButtonVariant.Secondary,
    glyph: VelaGlyph? = null,
    busy: Boolean = false,              // focusable, click ignored, stateDescription "Working"
    onClick: () -> Unit,
)   // positional order of the first four parameters and the trailing lambda is frozen (92 call sites)

@Composable fun VelaTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) // Role.Tab
@Composable fun VelaChoiceChip(label: String, selected: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true)                                               // Role.RadioButton
@Composable fun <T> VelaChoiceGroup(label: String, options: List<T>, selected: T, optionLabel: (T) -> String,
    onSelect: (T) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true)                         // selectableGroup()

@Composable fun Glyph(glyph: VelaGlyph, contentDescription: String?, modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current, size: Dp = VelaSize.Glyph)
```

Glyphs are cached `ImageVector`s built from inline SVG path strings (24-unit viewport, 2-unit round strokes).

### 3.8 `Artwork.kt` and `Cards.kt`

```kotlin
@Composable fun ArtworkFrame(
    modifier: Modifier = Modifier,
    shape: Shape = VelaShapes.Card,
    showFallback: Boolean = true,
    fallbackTitle: String? = null,
    glyph: VelaGlyph? = null,
    agentic: Boolean = false,           // fallback fill agentContainer instead of surfaceRaised
    progress: Float? = null,            // clamped via displayProgress; 4 dp accent edge
    content: @Composable BoxScope.() -> Unit = {},
)
/** model: URL String, Drawable or ByteArray (all current Tile.image types). Fallback stays until success. */
@Composable fun VelaArtwork(model: Any?, modifier: Modifier = Modifier, fallbackTitle: String? = null,
    glyph: VelaGlyph? = null, contentScale: ContentScale = ContentScale.Crop, shape: Shape = VelaShapes.Card,
    agentic: Boolean = false, progress: Float? = null)
/** Settles 300 ms, crossfades 400 ms (instant with reduced motion), draws the two scrims; nothing in high contrast. */
@Composable fun VelaBackdrop(model: Any?, modifier: Modifier = Modifier)

enum class CardFormat(val width: Dp, val height: Dp, val artScale: ContentScale) {
    Wide(224.dp, 126.dp, ContentScale.Crop), Poster(112.dp, 168.dp, ContentScale.Crop), App(160.dp, 90.dp, ContentScale.Fit);
    /** The one row-height formula: header 24 + HeaderToArt 12 + art + ArtToLabel 12 + CardLabel 40 + 12 bottom padding.
     *  CardRow, RowStatusStrip and SkeletonRow all size themselves from it (no layout jump by construction). */
    val rowHeight: Dp get() = 24.dp + VelaSpace.HeaderToArt + height + VelaSpace.ArtToLabel + VelaSize.CardLabel + 12.dp
}
/** Stateless visual: used by MediaCard and the Appearance preview. Label area is clearAndSetSemantics. */
@Composable fun MediaCardVisual(title: String, format: CardFormat, state: VelaInteractionState,
    modifier: Modifier = Modifier, image: Any? = null, progress: Float? = null, glyph: VelaGlyph? = null,
    kicker: String? = null, agentic: Boolean = false)
@Composable fun MediaCard(
    title: String,
    format: CardFormat,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    image: Any? = null,
    progress: Float? = null,
    glyph: VelaGlyph? = null,
    kicker: String? = null,
    agentic: Boolean = false,
    enabled: Boolean = true,
    onClickLabel: String = "Open $title",
    onFocused: () -> Unit = {},
)   // contentDescription = title + subtitle; stateDescription "N percent watched" when progress present
```

### 3.9 `Rows.kt`, `Pages.kt`, `Lists.kt`

```kotlin
@Composable fun VelaSectionHeader(title: String, modifier: Modifier = Modifier, supporting: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null)   // heading() semantics

/** Full-bleed: header inset 48 dp; LazyRow contentPadding (48 dp, 12 dp), spacing 16 dp, focusRestorer(). */
@Composable fun <T> CardRow(title: String, items: List<T>, key: (T) -> Any, modifier: Modifier = Modifier,
    supporting: String? = null, state: LazyListState = rememberLazyListState(),
    card: @Composable LazyItemScope.(T) -> Unit)
/** Same total height as a CardRow of [format]: header + strip; no layout jump between states. */
@Composable fun RowStatusStrip(title: String, format: CardFormat, kind: StatusKind, message: String,
    modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {})
@Composable fun SkeletonRow(title: String, format: CardFormat, modifier: Modifier = Modifier, count: Int = 5)
/** GridCells.Adaptive(format.width + CardGap), safe padding, focusRestorer(). */
@Composable fun CardGrid(format: CardFormat, modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(), content: LazyGridScope.() -> Unit)
fun LazyGridScope.fullSpanItem(key: Any? = null, content: @Composable LazyGridItemScope.() -> Unit)

/** LazyColumn + focusRestorer; breadcrumb, headline title, supporting text, trailing actions. */
@Composable fun VelaPage(title: String, modifier: Modifier = Modifier, breadcrumb: String? = null,
    supporting: String? = null, state: LazyListState = rememberLazyListState(), inset: Boolean = true,
    actions: (@Composable RowScope.() -> Unit)? = null, content: LazyListScope.() -> Unit)
fun LazyListScope.insetItem(key: Any? = null, contentType: Any? = null, content: @Composable LazyItemScope.() -> Unit)
fun Modifier.safeHorizontal(): Modifier       // padding(horizontal = SafeHorizontal)
fun Modifier.readingWidth(): Modifier         // widthIn(max = ReadingWidth)

/** Either the row is the click target (onClick != null, trailing non-focusable) or trailing holds controls. */
@Composable fun VelaListRow(title: String, modifier: Modifier = Modifier, supporting: String? = null,
    glyph: VelaGlyph? = null, tone: StatusTone = StatusTone.Neutral, onClick: (() -> Unit)? = null,
    enabled: Boolean = true, trailing: (@Composable RowScope.() -> Unit)? = null)
@Composable fun VelaToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, supporting: String? = null, enabled: Boolean = true)   // Role.Switch, "On"/"Off"
```

### 3.10 `Status.kt` and `Agent.kt`

```kotlin
@Composable fun StatusPanel(title: String, message: String, modifier: Modifier = Modifier,
    kind: StatusKind = StatusKind.Info, action: String? = null, onAction: () -> Unit = {},
    actionVariant: ButtonVariant = ButtonVariant.Secondary)
@Composable fun InlineStatus(text: String, modifier: Modifier = Modifier, tone: StatusTone = StatusTone.Neutral, live: Boolean = true)
@Composable fun WorkingIndicator(label: String, modifier: Modifier = Modifier, tone: StatusTone = StatusTone.Agent)
@Composable fun EmptyState(title: String, message: String, action: String? = null, onAction: () -> Unit = {})  // existing signature → StatusPanel(kind = Empty)
@Composable fun LoadingState(message: String, modifier: Modifier = Modifier)                                     // existing signature → WorkingIndicator(Neutral)

@Composable fun AgentLantern(indicator: AgentIndicator, onAsk: () -> Unit, onOpenActivity: () -> Unit, modifier: Modifier = Modifier)
// Idle: VelaButton("Ask Vela", variant = Agent, glyph = Sail) → onAsk. Working: "Working…" → onOpenActivity.
// NeedsApproval: "Needs your OK" (Caution glyph) → onOpenActivity. Polite live region on state change.
@Composable fun ApprovalNotice(question: String, remoteAnswers: Boolean, modifier: Modifier = Modifier)   // not focusable; no buttons
@Composable fun AgentTaskPanel(task: String, latestStep: String?, modifier: Modifier = Modifier,
    approvalQuestion: String? = null, remoteAnswers: Boolean = true, actions: @Composable RowScope.() -> Unit)
```

### 3.11 `Fields.kt` and `Dialogs.kt`

```kotlin
@Composable fun VelaTextField(value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, supporting: String? = null, error: String? = null, placeholder: String? = null,
    enabled: Boolean = true, readOnly: Boolean = false, secret: Boolean = false, revealSecret: Boolean = false,
    singleLine: Boolean = true, minLines: Int = 1, maxLines: Int = if (singleLine) 1 else 8, maxLength: Int = 8000,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default)
// Truncates input to maxLength; secret uses PasswordVisualTransformation unless revealSecret.
@Composable fun FieldShell(label: String, modifier: Modifier = Modifier, supporting: String? = null,
    error: String? = null, content: @Composable () -> Unit)

/** Requests [initialFocus] after first frame. Actions row at the bottom (FlowRow). */
@Composable fun VelaDialog(title: String, onDismissRequest: () -> Unit, initialFocus: FocusRequester,
    modifier: Modifier = Modifier, actions: @Composable FlowRowScope.() -> Unit, content: @Composable ColumnScope.() -> Unit = {})
/** Header actions at a stable top position; scrollable keyed body with focusRestorer. */
@Composable fun VelaSheet(title: String, onDismissRequest: () -> Unit, initialFocus: FocusRequester,
    modifier: Modifier = Modifier, headerActions: @Composable FlowRowScope.() -> Unit, content: LazyListScope.() -> Unit)
/** Cancel first and focused; confirm uses Danger when [danger]. */
@Composable fun ConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit,
    onDismiss: () -> Unit, danger: Boolean = false, cancelLabel: String = "Cancel")
```

### 3.12 Compatibility (WP1 must keep until WP2 lands)

Keep compiling with unchanged meaning: `VelaButton` positional order, `EmptyState`, `LoadingState`,
`LocalReducedMotion`, `LocalHighContrast`, `VelaTheme(settings)`, and `object VelaDesign` members
`SpaceSmall, Space, SafeHorizontal, SafeVertical, CardShape, PanelShape, PillShape, MinimumTarget,
FocusDuration, BackdropDuration, Night, Day, Type` (delegating to the new tokens; `SafeHorizontal`
becomes 48 dp, `SafeVertical` 27 dp; mark `@Deprecated` at WARNING level). After WP2 lands and `rg -n "VelaDesign" android/app/src/main/java` returns only `design/`, the coordinator may remove the compatibility alias during final integration. Moving `VelaButton`/`EmptyState`/`LoadingState` to new files is fine (same FQN).

### 3.13 `control/Overlay.kt` (visual only)

Colours from `VelaPalettes.overlay(highContrast)` read from `VelaApp.settings` at `show()`; `surface`
at 95% alpha (opaque in high contrast), 20 dp radius, 1 dp `line` stroke (2 dp `agent` stroke while a prompt is shown); header
row "Vela" (16 sp) with an `agent` dot, or "Vela needs your OK" (18 sp SemiBold) with a prompt; steps
`inkMuted` 16 sp, last three; prompt `ink` 20 sp; hint `accent` 16 sp, exact existing text; position
top-end with 48/27 dp margins, width ≤ 520 dp. No shadow layer (`setShadowLayer` removed; the solid
panel provides legibility). Flags, window type, lifecycle and `show/hide` signatures unchanged.

## 4. WP2 contract (package `app.vela.tv.launcher`)

### 4.1 New files and pure APIs

```kotlin
// TilePresentation.kt — imports only app.vela.tv.layout.* and app.vela.tv.design.VelaGlyph
internal data class TileAffordance(val verb: String, val glyph: VelaGlyph?, val agentic: Boolean)
internal fun tileAffordance(action: TileAction): TileAffordance
// LaunchApp → ("Open", null, false); SearchApp → ("Search in app", Search, false); OpenUri → ("Open", Link, false)
// PlayJellyfin → ("Open details", null, false); AskAgent → ("Ask Vela", Ask, true)
// HomeService → (service == "toggle" ? "Toggle" : service capitalised, House, false)
internal fun sourceLabel(source: Source): String?   // short, user-facing: "Jellyfin", "Plex · search in app",
                                                     // "Home Assistant", "Pinned", null for Apps/Watchlist

// LayoutEdits.kt — pure
internal object LayoutEdits {
    fun move(layout: HomeLayout, rowId: String, delta: Int): HomeLayout   // clamps at ends; unknown id → IllegalArgumentException
    fun remove(layout: HomeLayout, rowId: String): HomeLayout              // refuses to remove the last row
}
```

### 4.2 Compose adapters

```kotlin
internal fun TileStyle.cardFormat(): CardFormat     // WIDE→Wide, POSTER→Poster, APP→App
@Composable internal fun TileCard(tile: Tile, style: TileStyle, onFocused: (Tile) -> Unit,
    onSelect: (Tile) -> Unit, modifier: Modifier = Modifier)
// MediaCard(title, style.cardFormat(), onClick = { onSelect(tile) }, subtitle, image = tile.image,
//   progress = tile.progress, glyph/kicker/agentic from tileAffordance, onClickLabel = "${verb} ${title}")
@Composable internal fun TileRow(row: LoadedRow, onFocused: (Tile) -> Unit, onSelect: (Tile) -> Unit, modifier: Modifier = Modifier)
// error → RowStatusStrip(kind = Error, action = "Retry"); tiles → CardRow(key = Tile::key) { TileCard(...) }
```

**Agent-composed rows reuse exactly this path.** `HomeContent` renders each `LoadedRow` from
`app.settings.layout` in document order through `TileRow`; there is no per-source or per-author
composable. Selection always calls `TvShell.select(tile)` which opens the title sheet or calls
`perform(action)` → `HomeData.perform` → `Runner.performTile` (guarded). A pinned `AskAgent` tile is a
`MediaCard` with `agentic = true`, nothing else.

### 4.3 Shell and destination work

1. `ShellChrome.kt`: `TopBar` (sail + wordmark, `VelaTab`s for Home, Search, Apps, My list, House,
   Activity, Settings in a weighted `LazyRow` with `focusRestorer`, profile chip, `AgentLantern`).
   `agentIndicator(app.runner.running, app.runner.pending, last line)`; `remoteAnswers` from
   `DeviceStatus.read(app).accessibility` remembered per resume. Profiles/Routines/External control
   remain `Page` entries, reached from Settings and the profile chip; Back from them returns to Settings.
2. Home (`HomeActivity.kt`): `VelaBackdrop` + fixed hero (`VelaSize.Hero`, compact while Now Playing
   bar is visible) + `LazyColumn` of `TileRow`s with no horizontal content padding. Remove the
   `offset(-12)` row trick and the top `"loading"` item that shifts content; first load shows
   `SkeletonRow`s, refreshes keep stale rows. Pair panel is the first scrolling item when AI is not ready.
   End actions: "Arrange home" and "Ask Vela to change home" (opens `AskActivity`; it does not auto-send).
3. Search, Apps, My list, House: `VelaPage`/`CardGrid`/`CardRow`/`VelaListRow`; agent handoffs use
   `ButtonVariant.Agent`.
4. Activity (`ActivityPage.kt`): `AgentTaskPanel` when running (Stop task = Danger, Ask something else =
   Agent → `AskActivity`), `ApprovalNotice` when pending, history as `VelaListRow` with
   `taskTone/taskStateLabel`, "Ask again" (Agent). Initial focus never lands on Stop.
5. Settings hub (`SettingsHub.kt`): grouped `VelaListRow`s per design language §11.5; Appearance uses
   `VelaChoiceGroup`s writing the same `settings.copy(...)` updates as today, plus a non-interactive
   preview row of `MediaCardVisual` (each format, rest and focused) — this is the showcase.
6. Title sheet → `VelaSheet`; error dialog and management confirmations → `VelaDialog`/`ConfirmDialog`;
   `ManagementField` → `VelaTextField`; `ManagementList` → `VelaPage(breadcrumb = "Settings")`;
   Ask → `VelaTextField` + Primary Ask + Agent styling; Now Playing → `NowPlayingBar` styling with
   `ArtworkFrame` + `Image(bitmap)` (bitmap keeps its `isRecycled` guard). The docked bar renders only
   when a session exists; `PERMISSION`/`UNAVAILABLE` access becomes a caution `VelaListRow` in the Settings
   hub that opens the existing details dialog. Observer lifecycle, policy checks and `act()` are unchanged.
7. Arrange home (`ArrangeHome.kt`): list of rows (title, format, `sourceLabel`) with Move up, Move
   down and Remove… (ConfirmDialog), plus Restore default layout… (ConfirmDialog). Each edit derives from
   the current `app.settings.layout.value`, saves immediately via §4.4, keeps focus on the same button of
   the moved row (per-row `FocusRequester` keyed by row id), and shows busy/error as `InlineStatus`.

### 4.4 The only new effect path

```kotlin
// ArrangeHome.kt — same validated owner layout write, with atomic Runner admission
internal suspend fun saveOwnerLayout(app: VelaApp, layout: HomeLayout) = withContext(Dispatchers.Main.immediate) {
    val checked = Layouts.decode(Layouts.encode(layout)).getOrThrow()
    app.runner.withController(OWNER_LAYOUT_CONTROLLER) {
        withContext(Dispatchers.IO) { app.settings.setLayout(checked) }
    }
}
internal const val OWNER_LAYOUT_CONTROLLER = "owner-layout"   // coordinator-approved internal controller label
```

## 5. Dependency order

1. **WP1 phase A (phase A publication, blocking WP2 compile):** every file and signature in §3 exists and
   compiles, compat (§3.12) intact, palette + presentation tests pass, existing screens already render
   with the new palette/type via `VelaTheme`, `VelaButton`, `EmptyState`, `LoadingState`.
   WP1 phase B: glyph paths, focus ring polish, backdrop, overlay. Signatures do not change in phase B.
2. **WP3** starts immediately and is independent of WP1/WP2.
3. **WP2** starts immediately on pure files and tests (§4.1), then integrates on WP1 phase A.
   It never edits `design/**`; a missing capability is reported to WP1 through the coordinator.
4. **Coordinator cleanup:** remove `VelaDesign` only after all callers migrate (§3.12).
5. Coordinator integrates, runs the combined checks (§7) and owns any cross-package fix.

Coordinator integration: all launcher callers now use the semantic APIs, so the
temporary `VelaDesign` alias has been removed. Held key, direct-pointer and indirect
input eligibility is implemented by the single-focus-target `ActivationInputNode`;
the actual-handler harness covers cancellation, overlap, event expiry and repeats.
See [integration evidence](evidence/ui-integration-2026-10-31.md) for executed checks.

## 6. WP3 contract (phone)

### 6.1 Structure and tokens

- Tokens per design language §4.2 with `light-dark()`; spacing `--space-1…6` = 0.25/0.5/0.75/1/1.5/2.5 rem;
  `--gutter: clamp(1rem, 0.5rem + 2vw, 2.5rem)`; radii `--radius-control: 999px`, `--radius-panel: 1.25rem`,
  `--radius-field: 0.75rem`; motion `--ease-out: cubic-bezier(0.23, 1, 0.32, 1)`, 160 ms press, 200 ms colour.
- Type: body `1rem/1.5`; `h1` `clamp(1.75rem, 1.4rem + 1.5vw, 2.5rem)`; `h2` `clamp(1.375rem, 1.25rem + 0.6vw, 1.75rem)`
  serif; `h3` 1.125rem sans semibold; `.hint` 0.9375rem; inputs `max(16px, 1rem)`.
- `body { container: page / inline-size }`. `main` is one column; `@container page (width > 60rem)` makes
  `grid-template-columns: minmax(20rem, 26rem) minmax(0, 1fr)` with new wrapper elements
  `<div class="primary-column">` (chat, remote) and `<div class="manage-column">` (connections, layout,
  profiles, memory, routines, external). `#unpaired` spans both. Remove the `auto-fit minmax(28rem)` grid.
- Sticky header (wordmark with an inline SVG sail, `#connection` status pill); `.section-nav` becomes a
  single horizontally scrolling lane (`overflow-x: auto`, `scroll-snap-type: x proximity`); sections get
  `scroll-margin-block-start` equal to the header height.
- Logical properties only; no `left/right/top/bottom`, `margin-left` etc.

### 6.2 Component styles

| Element | Style |
| --- | --- |
| `button` (default) | `--raised` fill, `--ink`, pill, min-block-size 44px, `:active` scale 0.97 (transform transition inside reduced-motion no-preference) |
| `button.primary` | `--accent` / `--on-accent`; `:focus-visible` outline colour `--accent` |
| `button.danger` (new class on confirm-first destructive buttons: `#recoverHousehold`, `#clearMemories`, `#revokeExternal`, profile/memory/routine delete buttons rendered by script) | `--raised` fill, `--danger` text and 1px border |
| `button.agent` (`#allow` stays primary; Ask submit becomes agent) | `--agent-container` fill, `--agent` text |
| fields | `--canvas` fill, 1px `--line-strong`, `--radius-field`; `:user-invalid` `--danger` border + message |
| `#pending` | `--agent` 2px border, `color-mix(in oklch, var(--agent) 14%, var(--surface))` fill, heading "Vela needs your OK" |
| `#log .you / .said / .step / .error` | §12 of the design language |
| `#tasks article[data-state]` | leading dot coloured by state (`running` agent, `complete` success, `failed` danger, `canceled` muted) + state word |
| `.remote` | circular keys 56px, OK 64px with 2px `--accent` ring; Back/Home pills |
| focus | `:focus-visible { outline: 3px solid var(--accent); outline-offset: 3px }`, never `outline: none` |
| hover | only inside `@media (hover: hover) and (pointer: fine)` |

### 6.3 Browser floor and fallbacks

Full fidelity: iOS/Safari 16.4+, Chrome/Android WebView 111+, Firefox 113+ (oklch, `color-mix`,
container queries, `:focus-visible`). `light-dark()` needs Safari 17.5 / Chrome 123 / Firefox 120, so one
`@supports not (color: light-dark(white, black))` block redefines every token with the hex fallbacks
under `prefers-color-scheme` (this block also covers browsers without oklch). `color-mix()` derived values
get explicit hex values in that block. `field-sizing: content`, `text-wrap: balance/pretty` and
`scrollbar-gutter` are progressive enhancements. `prefers-contrast: more` swaps to HC values;
`forced-colors: active` keeps control borders.

### 6.4 Script and markup limits

- No ID renamed or removed; no request, lock, polling, token or approval logic changed.
- Allowed script edits: adding `className`/`dataset.state` to elements the script already creates
  (log lines, task articles, management articles and their buttons, e.g. `danger` on delete buttons).
- Coordinator correction after browser QA: explicit entry to the profile, memory or routine editor
  resets that form's native validation state before restoring every draft field. This does not run
  during polling, reloads or conflicts and does not change request bodies, revisions or locks.
- Markup may gain wrappers, classes, `aria-*` and the inline SVG sail. Attributes must stay double-quoted.
- The test harness detects `hidden` with `/\bhidden\b/` on the whole attribute string, so
  `class="visually-hidden"` or `aria-hidden="true"` would mark an element hidden. Either avoid those
  strings or first change the harness to read the boolean `hidden` attribute only.
- Form tags keep the form `<form id="…" …>` (the harness anchors on `id` right after `form`).
- **No new `<button>` elements.** The script captures `main button, main input, …` once at load and gates
  them on connection; the harness treats every button except `#reconnect` as a gated control. The header,
  `.section-nav` (links stay `<a>`) and `#connectionHelp`/`#reconnect` stay outside `main`.

## 7. Acceptance (coordinator update)

Workers use isolated compiler/test outputs and report exactly what ran. They do not run shared Gradle tasks while peers edit the same project, and they do not mutate Git. The coordinator runs the complete Android/phone checks after all writers are quiescent.

- WP1: meaningful pure palette contrast/selection and presentation-state tests, isolated Compose compilation when feasible; preserve existing tests. Palette invariants: text ≥4.5, HC text ≥7, essential boundaries/focus/accent ≥3. Report any necessary token correction with evidence. Overlay flags/type/hint text remain unchanged.
- WP2: pure tile-affordance/source-label and layout move/remove/validation tests; preserve existing management/media/control tests. No new effect path beyond guarded existing callbacks and §4.4. All changed production surfaces consume the shared components. No hardcoded colour or shape literals in launcher adapters.
- WP3: all **35 existing phone-script tests** pass. Update the test harness's boolean hidden-attribute parser only if markup additions require it, with a regression for aria-hidden/class names. Preserve IDs, request/approval locks and token logic. No tests that merely mirror CSS literals; visual focus, responsive layout and colour states are checked in the browser.

Combined checks: `./gradlew --no-daemon testDebugUnitTest lintDebug lintFirmware assembleDebug assembleFirmware`, the Node phone suite, relevant pure regressions and `git diff --check`.

### Visual proof

The coordinator's physical target is host `local`, device `10.0.0.209:5555`. The baseline debug APK launched on the owner's Tube at 1920×1080; reported density320 gives the 960×540dp baseline. The image `evidence/vela-before-shield-night.png` is the coordinator's real capture, with its pairing QR deliberately redacted. The raw QR rendered correctly; the masked square is not a UI bug. This image will be committed with the design increment.

**Physical-device testing is paused at the owner's request (2026-10-31).** Launching apps on the Shield steals the TV input during normal use. The coordinator closed this thread's Device panel without shutting down the Shield. Do not reconnect, install, launch, navigate, capture or otherwise operate the physical Shield until the owner explicitly resumes testing. Code, build, fixture and independent browser work continue. The following TV acceptance checks remain pending.

Only the coordinator performs GUI/device actions. Capture new TV screens in Night/Day/High Contrast and exercise D-pad focus, row ends, Back/restoration, disabled/busy activation, reduced motion, text entry, sheets and layout reordering. Verify real measured app constraints; do not change physical display size/density or claim 4K acceptance from 1080p evidence. A paused valid media session retains the Now Playing bar.

Phone QA uses the existing CUA Spaces shared Mac desktop and native browser controls, following current tool routing. Target 320/390/768/1280 CSS widths, both themes and 200% zoom where the installed browser permits; report exact measured viewports and any unavailable cases. No `preview_*` or HTML-preview invocation for unasked verification. Workers do no browser/device actions and never substitute source or old screenshots for visual proof.

Nominal component heights are lower bounds at larger text scales or short surfaces: grow or scroll rather than clip labels. Full UI completion requires observed changed screens; the existing owner review keeps the PR unmerged.

## 8. Open technical questions

1. **TV layout controller resolved.** The coordinator accepts `owner-layout` within the authorized composable-home work. Use the Runner admission wrapper in §4.4. No new owner approval or backend schema change is required.
2. **Disabled-but-focusable controls.** Changing `clickable(enabled = false)` to focusable-but-ignored
   alters TalkBack and D-pad behaviour everywhere `VelaButton(enabled = false)` is used. No guard test reads UI
   state, but device QA must confirm no accidental activation on key repeat.
3. **Pivot scrolling.** Keeping the focused row at a fixed vertical position needs `BringIntoViewSpec`
   (`ExperimentalFoundationApi`). The contract uses default bring-into-view; adopt pivoting only after device QA.
4. **D-pad text entry.** `OutlinedTextField` IME behaviour on stock Android 11/API 30 TV (does focus open the
   keyboard, does Back leave the field) is unverified; `VelaTextField` keeps the M3 field until tested.
5. **Serif face.** `FontFamily.Serif` should resolve to Noto Serif on the Shield's stock and LineageOS
   images; if a build lacks it, Android falls back to a default serif. No font download is permitted.
6. **Coil and Plex bytes.** `Tile.image` may be a `ByteArray` (Plex); `VelaArtwork` must keep that working
   exactly as today's `AsyncImage(t.image)` call does. WP1 confirms with the existing call shape.
7. **Gradle available.** The coordinator has verified full builds with JDK21 and Android SDK37. Workers use isolated outputs; the coordinator performs combined Gradle verification after writes stop.
