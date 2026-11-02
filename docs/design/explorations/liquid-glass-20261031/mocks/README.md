# Vela — Night water, morning light

Finished **concept mockups**, 2026-10-31. One recommended warm cinematic glass direction, authored only in this directory. These are illustrations and local front-end state changes, not application or service results.

## Open / render

`index.html` is the self-contained gallery/prototype: **360,921 bytes**, inline CSS/JS, embedded photography and font, no runtime network dependency. Open as a file or pass its complete text to `html_preview`, then `html_render` in the parent. No server is needed. The outer gallery is transparent and uses host theme variables. Its compact concept label and interaction annotations sit outside the product frame.

Select any state from **View**. **Day/Night** and **Opaque/Glass** work locally. Buttons demonstrate Ask → listening specimen → reviewed draft → explicit submission → Working, details → Opening, receipt acknowledgement → retained Undo, restore confirmation, local preferences, phone approvals and remote feedback. Unmocked destinations acknowledge their name instead of opening an unrelated screen. No microphone, playback, pairing, approval, infrastructure or persistence API is called.

To independently render a state without interacting with the gallery:

```sh
python3 docs/design/explorations/liquid-glass-20261031/mocks/select-view.py ask
python3 docs/design/explorations/liquid-glass-20261031/mocks/select-view.py editor --day
python3 docs/design/explorations/liquid-glass-20261031/mocks/select-view.py phone --clean
```

The command writes complete ready-to-render HTML to stdout; it never edits the export. `--clean` removes the external gallery controls/annotations for image export. Retain them for presenting concepts to the user. Default renders: 1200px for TV with annotations; 409px clean phone gives a **393px product frame** plus the host's 8px gutter on each side.

The underlying setting is the JSON script `id="initial-view"`, with `view`, optional `theme` (`night`/`day`), `controls`, and `notes`. `window.vela.show(view, theme)` is also available. `window.vela.views` lists all 26 view IDs.

| Full composition | Initial view IDs |
| --- | --- |
| Matched Home | `home-night`, `home-day`, `home-entry` |
| Sparse / scrolled Home | `apps`, `app-focus` |
| Contextual Ask | `ask`, `ask-draft`, `ask-listening`, `ask-unavailable` |
| Exact title details / Resume | `details`, `opening` |
| Person-first preferences | `editor` (Day available through theme) |
| Companion phone | `phone`, `phone-approval` |
| Layout / recovery | `arrange`, `undo`, `undo-kept`, `restore`, `busy` |
| Agent / honest outcome | `working`, `approval`, `unknown`, `shell-states` |
| Resilience / optics | `opaque-home`, `opaque-ask`, `materials` |

`prototype.html` is the editable source with `ASSET:` references. Run `python3 …/mocks/build.py` after changes to regenerate `index.html`. This is only an HTML asset packer, not an application build. No package installation is needed to regenerate it.

## Design choices

- **Manrope**, embedded Latin variable subset, weights 500–800; fallback `system-ui, sans-serif`. The sans is a proposed product font dependency. Georgia italic appears only as lettering on the invented *Sunday, Slowly* artwork.
- A single 52-unit navigation plane, compact retained title context, complete content/app rails, and a luminous independent 3-unit focus perimeter with a dark/light separator. The selected tab uses a small underline. The agent owns a fixed-width slot; the three-state board includes a long person name without shifting adjacent targets.
- Artwork remains crisp. Cached soft photographic crops supply the glass's transmitted color, combined with local tint, an upper/leading reflection, a darker return edge, contact shadow and broad separation. Dense reading regions and opaque fields keep information stable. There is **no `backdrop-filter`, live refraction or animated blur**.
- Night uses smoke, pearl ink and a small champagne edge; Day uses pearl, deep ink and the same colored art. Periwinkle is localized to Vela. Sparse Home uses a quiet tonal light field without invented library content or a setup billboard.
- TV is a **960 × 540 logical composition**, with 48/27 safe margins. Scaling is gallery presentation, not a proposed native responsive implementation. Remote routes and proposals are annotated outside each frame. Arrow-key navigation is a local geometric demonstration, not proof of native D-pad or IME behavior.
- No perpetual shimmer or decorative waveform. Press feedback is short; movement is gated by reduced-motion preference. All states remain legible in still frames.

## Behavioral continuity / proposals

Media OK means details; app OK means Open. Details starts on Back, then offers named Resume from Jellyfin. Opening is explicitly unconfirmed playback. External-app unknown outcome has no blind Retry or success flourish. Ask never starts a microphone or submits speech automatically; the listening specimen is explicitly simulated and leads to a reviewable draft. Stop task remains distinct from dismissing presentation.

The TV approval is **read-only**, with exact target/effect and `OK to allow · Back to cancel`. There are no approval buttons or focusable controls inside that notice; the underlying content focus remains visible. The paired phone alone shows Allow once / Decline, with prompt identity and local-only acknowledgement.

Undo is conditional, one step and session-only. Keep layout acknowledges an already-saved receipt; Undo stays directly beside Arrange home afterward. Busy Undo keeps its reason and control position. Restore names Continue watching, Apps, Recently added and My list exactly, with Cancel focused. Arrangement buttons illustrate the receipt path; the prototype is not a full layout/history engine.

The compact hierarchy, contextual Ask presentation/return focus, bounded person-first editor, language-name picker/custom-tag entry, and optical rendering tiers are **design proposals**, not existing backend claims. PIN/shared-home copy stays explicit; Autoplay is simply Off/On. Maintenance recovery remains discoverable and confirm-first; no recovery is executed. Ordinary full Search, House, Activity, library setup and management workflows are outside this focused prototype.

## Evidence and limits

Actually inspected current PNGs before designing: `home-media-night.png`, `home-apps-only-night.png`, `ask-day.png`, `profile-editor-day.png` from the two 20261031 evidence sets. Read the coordinator synthesis first and all three review reports.

Used **`html_preview`**, and visually inspected the resulting PNGs for Home Night/Day, sparse Home, app focus, Ask empty/draft, details, preferences, both phone compositions, receipt, acknowledged Undo, Restore, busy Undo, unknown outcome, TV approval, shell states, materials and opaque Home/Ask. The final core exports have all artwork loaded and one illustrated focus target per interactive TV composition. Observed issues corrected: editor note escaped its panel; phone feedback competed with composer; phone image crop repeated across a panel; Day counter lacked a stable backing; modal/background focus duplicated; Resume reused a search state. The phone approval now has clear space above the composer.

**64 local DOM/state checks across 26 initial views passed**, run inside `html_preview`: frame bounds, expected focus counts, empty/typed Ask admission, explicit listening → draft → Ask, acknowledgement retaining Undo and its busy gating, Undo removing the receipt, read-only TV notice, one-shot local phone decision, local Save feedback, and named Opening. `verify-local.js` records that check script; append it as a script to a temporary preview document to rerun. This is proportional prototype verification, not application regression testing.

Thirteen representative **actual `html_preview` PNGs** are in `renders/concept-*.png`. `renders/preview-evidence.json` records tool viewport sizes, content heights and captured console metadata. TV exports are 1200 × 682 or 1080 × 615 including the host gutter; phone exports are 409 × 806 including the gutter. Clean exports are concept images, not app captures.

No physical Shield, device tools, app build, GUI session, production code, existing contract/report, Git/PR, server, credentials or infrastructure was touched. No `html_render` was used in this child. **Shield testing remains paused.** No sofa-distance, real-content contrast certification, large-text/IME, actual return-focus, native blur, GPU, memory, thermal or playback validation is claimed. The documented API30 stock limit does not ban glass: a cached artwork/tonal tier and an opaque tier are plausible proposals; their native implementation/performance remain unmeasured.

## Coordinator verification and presentation

The parent independently inspected the exported images and rendered the actual
HTML for Home Night/Day, Ask, Day editor, phone approval at 393px product width,
Undo, details, materials, opaque Home and the 390px gallery. No console warnings
or errors were reported in these previews. The finished interactive gallery was
shown in the parent through `html_preview` then `html_render`.

The approval specimen now asks about opening a garage door, matching the current
security guard; ordinary light control does not normally require confirmation.
Its phone PNG was re-exported after this copy correction. This changes concept
copy only. The dedicated opaque views also now offer a correctly labelled Glass
comparison that preserves the chosen theme. New checks first reproduced the
broken return path; the final **72 local checks across 26 views pass**, including
both opaque compositions in both themes. These remain prototype checks, separate
from the Android suite. [Coordinator evidence](renders/coordinator-evidence.json)
records preview dimensions, render audits, issues and the final local result.
