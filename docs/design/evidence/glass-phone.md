# Glass companion implementation · 2026-10-31

Phone owner implementation for the approved liquid-glass contract; supplied baseline
`9d6a2161ad71b526f26d07b5511f1cb2e1f5afc9`. Read the current contract first,
inspected `concept-phone.png`, `concept-phone-approval.png` and the prototype HTML,
and applied the installed good-css skill.

Only these files were written:

- `android/app/src/main/assets/phone.html`
- `android/app/src/test/ui/phone.test.cjs`
- `docs/design/evidence/glass-phone.md`

The production page now uses Manrope, a restrained warm sail, an ambient gradient
scene, smoked Night and pearl Day panels with a coherent upper reflection, flat
child controls, and independent focus outlines. Conversation, Remote and Activity
are the primary anchors; all management destinations retain their own anchors and
full forms. The request composer follows the remote, with submission errors and
Stop beside it. The approval card precedes conversation history, uses the actual
question, exposes the exact prompt ID as text, and changes the greeting only while
an authenticated pending prompt exists. No fictional conversation, catalog, photos,
room name, or generated media was added.

The existing IDs, seven remote keys, forms, hidden attributes, pairing/authentication,
route schemas, request/remote/approval locks, captured revisions, conflict handling,
unsaved drafts, explicit management loads, routine identity, private export and
one-shot credential handling remain in their existing paths. Profile language
fields retain the exact existing BCP-47 population, custom tags and save behavior;
no language-control adaptation was introduced. Phone theme selection still follows
the phone's preference, independently of the existing TV appearance field.

The approved 27,140-byte Latin WOFF2 is embedded exactly once. Its full unchanged
OFL attribution/license is available in a native disclosure at the page footer.
The font SHA-256 is
`20deaeef8b713bd60a54aab7cf7814fd44855b3895129715f1f6c16f2e877691`.
The self-contained HTML is 114,246 bytes.

**CSP compatibility:** read-only inspection of `web/PhoneApi.kt:41` found that the
current server policy allows inline scripts/styles but does not allow `data:` font
URLs. The page therefore registers the embedded bytes using the standard
`FontFace` buffer-source constructor, rather than a font URL. Loading is asynchronous,
failure leaves system type usable, and initialization does not block pairing or
polling. No backend, CSP, asset route, font service, package or dependency change is
needed. This uses the documented [FontFace constructor](https://developer.mozilla.org/en-US/docs/Web/API/FontFace/FontFace)
and its [binary-source loading algorithm](https://drafts.csswg.org/css-font-loading/#font-face-constructor).

**Verification performed:**

- `node --test android/app/src/test/ui/phone.test.cjs`, Node `v26.13.0`:
  **40 passed, 0 failed**. The full original 36-test suite is retained. Four new
  behavior tests cover exact/untrusted displayed approval identity and clearing,
  replacement prompts during refused/in-flight decisions, exact approved font bytes
  without a URL/request, and constructor/asynchronous font failures while pairing,
  polling and actual request submission continue. Existing approval/disconnect tests
  also assert the presentation follows the current prompt rather than stale state.
- A one-off HTMLParser integrity check confirmed unique DOM IDs, valid local
  section anchors, the seven existing remote keys, and the full unchanged OFL text.
  The font byte identity is exercised by the shipped-script behavior test.
- Final disconnected `html_preview` inspections: **Night at 320, 393 and 1280 CSS px;
  Day at 393 CSS px**. Captures were cropped at 1100px, so they show the initial
  conversation, remote, composer, start of Activity, and at desktop width the start
  of Connections. No backend response or application state was fabricated.
- Preview-only adjustments removed the tool's outer HTML gutter, replaced
  `min-block-size: 100svh` with `0` because the tool rejects viewport units, and
  bounded/cropped the body. A diagnostic script measured layout; a meta policy
  copied the server's font-relevant CSP directives without the header-only
  `frame-ancestors` directive. None of these inspection adjustments ships.
- Under that policy all final sizes reported `Manrope: loaded`, document width
  equal to viewport width, no horizontally out-of-bounds laid-out controls/links,
  and no measured button/input/select/textarea/summary/nav target below 44px.
  Checkbox inputs were excluded from that geometry check; their labels retain
  48px targets. Initial inspection exposed a wrapping Home label and a clipped
  Manage link at 320px; both were corrected and re-inspected.
- The only preview console error was the expected disconnected request:
  `net::ERR_ACCESS_DENIED http://t3-page.localhost/api/state`. These previews never
  connected to a TV. No `html_render`, device/GUI tooling or server was used.

**Fallbacks and limits:** the baseline panels are opaque; supported browsers may
add [backdrop filtering](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/backdrop-filter).
The retained older-color-syntax palette keeps the single-column interface usable
without modern color functions. Unsupported container queries simply retain the
phone layout. Reduced transparency and increased contrast remove blur, gradients
and reflections; forced colors use system colors and explicit boundaries.
Press motion exists only in the no-reduced-motion branch, with persistent static
feedback. [Reduced transparency](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/At-rules/@media/prefers-reduced-transparency)
has browser-dependent support; [forced colors](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/At-rules/@media/forced-colors)
and contrast rules remain separate. No new runtime library or network dependency
was introduced; unavailable font APIs or decode failures use system fallback type.

Connected approval rendering, populated conversation/activity/management editors,
actual browser/OS forced-colors and reduced-motion/transparency states, enlarged
text, keyboard/screen-reader interaction, mobile browser performance and final
visual comparison remain the coordinator's acceptance work. Those states were
behavior-tested where covered by the Node suite, **not visually accepted here**.
The disconnected previews and passing tests are not connected visual acceptance.
There is no outstanding cross-owner code dependency. No Git/PR operations, Gradle
builds, shared build outputs, credentials, infrastructure or firmware actions were
performed. The paused physical Shield was not contacted.

Coordinator follow-through: [combined checks, actual captures and remaining limits](glass-native-20261031/README.md).
