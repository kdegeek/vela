# WP3 phone presentation implementation

2026-10-31 · baseline `0809edfb31d2fac467dc55e2b67fd93d9cf777df` · shared checkout
`t3/explore-agentic-tv-pivot`.

Implemented against the complete governing `vela-design-language.md` and
`vela-component-contract.md`, including coordinator ownership and the physical-device pause.
Read the native palette, tokens, glyphs, button, field and agent implementations for parity;
none of those APIs or files were changed. Applied the `good-css` skill and its foundations,
layout, interaction, scrolling, spacing, typography/media and motion guidance.

## Owned changes

- `android/app/src/main/assets/phone.html`: all 16 shared Day/Night OKLCH roles,
  `light-dark()` selection, exact hex fallbacks and explicit derived-colour fallbacks in one
  legacy-support block; both native HC palettes converted to OKLCH with matching hex fallbacks.
  Forced colours keep system-colour boundaries and focus rings. HC press feedback remains
  visible with reduced motion enabled.
- Flat sections, serif headings, a sticky sail/wordmark/status header and scrolling section
  links; one column by default and the contracted primary/management grid above 60rem.
  The header and anchor offset share one size token. Long connection errors scroll inside
  a keyboard-focusable status pill rather than expanding over the page.
- Circular 56px remote keys, 64px OK with brass border, Back/Home pills, native form controls,
  44px minimum control targets, visible focus/press states, capability-gated hover and
  motion only under `prefers-reduced-motion: no-preference`. At enlarged text sizes the
  remote can scroll horizontally without dropping keys or changing directional geometry.
- Periwinkle is confined to agent replies/actions, approvals and running task indicators.
  Approval keeps Allow once primary/brass, adds the heading “Vela needs your OK”, and retains
  the displayed prompt identity. Recent task indicators retain their existing state words.
  Destructive and confirm-first controls gain Danger styling.
- Long content wraps without truncation; management lists and activity are keyboard-scrollable.
  Native validation is preserved, with adjacent textual feedback for `:user-invalid` where
  supported. Textarea sizing and text wrapping enhancements degrade to native controls.
- Exactly two script additions: `article.dataset.state = task.state` for existing task articles,
  and `className` selection for existing management buttons. No event, request, polling,
  token, approval, control lock, draft, or API changes. No buttons added.
- `android/app/src/test/ui/phone.test.cjs`: only the boolean `hidden` attribute parser and one
  regression covering ARIA, class/title values, unquoted values and actual boolean attributes.
  In particular, `hidden="false"` remains hidden, as HTML requires.

## Executed checks

1. Before editing: `node --test android/app/src/test/ui/phone.test.cjs` — **35 passed, 0 failed**.
2. After editing: the same command — **36 passed, 0 failed** (35 original + 1 parser regression).
3. A read-only inline Python comparison against `git show HEAD:android/app/src/main/assets/phone.html`
   confirmed that removing the two presentation assignments makes the script byte-identical
   to baseline. Its HTMLParser check confirmed all **105 existing IDs**, **5 forms with `id`
   first**, **81 controls in their original order**, and **12 boolean hidden attributes** remain;
   tags balance and IDs are unique.
4. The same source check confirmed balanced CSS blocks, one legacy colour-fallback block,
   and no physical-side declarations, shadows, gradients, backdrop filters, `transition: all`
   or removed focus outlines. These are source checks, not browser acceptance.
5. `git diff --check -- android/app/src/main/assets/phone.html android/app/src/test/ui/phone.test.cjs docs/design/evidence/wp3-phone.md`
   — passed. A separate inline Python scan of these three files also passed for trailing
   whitespace and missing final newlines, including this new, untracked evidence note.

## Pending checks — 5 root-owned items

1. Observe the phone at measured 320/390/768/1280 CSS widths in Day and Night, including
   200% zoom where supported; inspect both one-column flow and the wide primary/management grid.
2. Observe keyboard focus, anchor offsets, scrolling section links, native validation and
   remote keys, including wrapped labels and long disconnected-status/error/content fixtures.
3. Observe high contrast, forced colours and reduced motion, including combined HC + reduced
   motion press feedback and the legacy colour fallback in a suitable browser.
4. Observe pending approval, running/complete/failed/canceled tasks, connection loss and
   management editor fixtures. Browser fixtures and acceptance belong to the separate worker/root;
   this worker used no server, browser, GUI, device, or credential-bearing screenshots.
5. After all writers finish, root runs combined Android checks
   (`./gradlew --no-daemon testDebugUnitTest lintDebug lintFirmware assembleDebug assembleFirmware`),
   the final Node suite and repository whitespace checks, then handles PR delivery.

No shared Gradle build, Git mutation, physical Shield operation, SSH, infrastructure,
credential access, firmware work or write outside the three owned files was performed.
No visual acceptance or screenshot proof is claimed. Physical Shield testing remains paused.
