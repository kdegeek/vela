# Vela: liquid-glass design exploration

User direction, 2026-10-31:

> Have 2 astra subagents, and one Fable subagent review the design adversarially,
> and then have a 6.1 Sol subagent generate mocks- I want something tasteful,
> beautiful, and joyful to use. I looooooove the modern liquid-glass look

This is a design review and mockup assignment. Production UI, backend behavior,
firmware, devices, dependencies and deployment remain outside this increment.
The user's new material preference supersedes the previous visual document's
flat/no-glass restrictions for this exploration. Do not dismiss that preference
by treating the old contract as immutable; evaluate how to make glass work well.
Do retain the current product/effect boundaries.

## Product continuity

- Entertainment-first Home: Continue watching, Apps, Recently added, My list.
  Preserve custom rows and an obvious, reversible layout-change experience.
- The agent is capable and available, with a compact entry and useful contextual
  presence. Its interface does not take over everyday browsing.
- Native TV is the primary experience. A D-pad, viewing distance and content
  artwork are first-class design inputs; phone layouts should belong to the same
  family. Settings/management need warmth and clarity as well as Home.
- Existing confirmations, exact-target control, truthful playback states and
  profile/controller boundaries stay in force. TV approvals remain read-only,
  answered through remote OK/Back or the paired phone.
- Physical Shield testing is explicitly paused. No device access, launches,
  captures, builds, Git mutations, credentials, services or infrastructure work
  belongs to the reviewers. Do not request an interruption window.

## Evidence

Current application source is commit `4006f179be9d9c068bf05cb0a943c2e0e5e7a3cd`.

- `docs/design/evidence/home-entertainment-20261031/`: current Home, populated
  and apps-only in both themes, media/app focus, Undo, Restore defaults. Artwork
  is deliberately synthetic test-pattern material, not intended art direction.
- `docs/design/evidence/visual-corrections-20261031/`: profiles, editor, Settings
  and Ask captures in both themes. These precede the Home change; their other
  screen structures remain relevant.
- `docs/design/vela-design-language.md` and `vela-component-contract.md`: the
  current implemented direction and component language, subject to this new brief.
- `android/app/src/main/java/app/vela/tv/design/` and `launcher/`: implementation
  evidence where useful. Do not mistake a source audit for visual observation.

## Independent reviews

1. Astra: visual hierarchy, art direction and material language.
2. Astra: interaction, D-pad focus, joyful everyday flows and resilient glass.
3. Fable: independent critique of identity, emotional quality, copy and coherence.

Each reviewer owns one separate report, inspects the actual captured images, and
distinguishes observed issues from hypotheses or implementation constraints.
No critique should be padded with invented defects. Deliver concrete redesign
instructions and ranked issues that the mockup designer can act on.

## Mockup stage

Start one GPT-6.1 Sol agent after all three reviews are available. Synthesize
their strongest findings into a coherent brief, resolving disagreement rather
than treating every suggestion as mandatory. Produce finished visual mockups
with credible content artwork, disciplined glass materials, clear remote focus,
Night/Day treatments, an agent interaction and a supporting management/phone
surface. A small set of distinct art directions is useful only if each is
finished enough to compare and one direction is clearly recommended.

Show the result in the parent thread through `html_preview` then `html_render`.
Label conceptual screens as mockups outside their product UI; do not imply
native implementation, live service behavior or hardware performance has been
verified. The coordinator inspects the rendered work before delivery.
