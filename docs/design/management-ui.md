# Household, memory, routine and external-control surfaces

Date: 2026-10-31. Implementation worker scope only. No Git mutation, shared Gradle
build, device operation, deployment or infrastructure change was performed.
The earlier UI work documented in `ui-review.md` remains in the shared checkout.

## Mounted application surfaces

`launcher/TvShell.kt` mounts `ProfilesPage`, `RoutinesPage` and
`ExternalControlPage` as destinations and links them from Settings. Its header
shows the current household profile, or loading/unavailable state. A household
revision change closes stale title actions and refreshes Home, rows and search.
Existing navigation focus restoration, adaptive grids, title-sheet behavior,
Activity Stop and approvals remain. Each management list has `focusRestorer` and
scrolls; long editors are full pages with IME padding and Save/Back at their ends,
not forms squeezed into a fixed-height dialog. Confirmation dialogs contain only
the bounded action explanation and Confirm/Cancel.

Every TV mutation uses the backend-owned `VelaApp.management: OwnerManagement`.
No TV management code writes schema files, opens a second store, mutates a domain
engine directly or bypasses controller ownership. The exact facade methods are
published in `application-api.md` and implemented in `runtime/OwnerManagement.kt`.
The UI uses the application-lifetime store/engine flows for readiness and live
state, while mutation authority stays in the facade. Loading, initialization
errors, stale revisions and busy-control errors remain visible. Profile startup
observation reconnects when household initialization/recovery supplies a store.

TV functionality:

- Create adult profiles, rename and edit audio/subtitle language lists, subtitle
  intent and autoplay; switch with an explicit drain/context-clear confirmation;
  delete only inactive, non-last profiles after confirming all owned memories.
  Existing kind, restrictions and provider/library preferences are preserved.
- Open memory for any selected profile; list in revision-bound 20-entry pages;
  explicitly save/edit exact text, kind, tags and sharing; confirm deletion or
  whole-profile memory clear. New entries default to private. The trusted facade
  assigns TV provenance. The editor identifies the memory owner even when it is
  not the currently active profile. Explicit export shows private JSON; use the
  phone to download that JSON to a chosen location.
- List/create/edit/enable/disable/delete routines, run now, cancel a run and
  restart the current definition. Show actual durable history states and queue,
  start and finish timestamps, including exhausted one-shots. Forms support
  offset-bearing one-shot/interval instants and named-zone weekly/monthly times,
  ISO weekdays, month-end clamping, missed-run policy and deadlines. Saving binds
  the definition to the current profile under the backend policy. The UI explains
  that stale/mismatched bindings fail closed and require a deliberate edit/Save.
- Inspect, explicitly enable/rotate or revoke external control. Enable returns a
  dedicated credential once; it is masked by default, never saveable or logged,
  and can be discarded. Revoke uses the facade's stop/drain path. The UI describes
  loopback-only access and the need for a separate tunnel from another computer.
- Recover household storage through the facade after confirming that work will
  stop. This reconciles persisted data and does not reset/delete the schema.

Profiles are personalization scopes without owner PIN authentication. These
surfaces do not claim OS-wide child security. Creating child policies or editing
allowlists is not offered by these preference editors; an existing restrictive
profile keeps its exact restrictions, and enforcement remains in the execution
paths. Household layout and watchlist are explicitly described as shared.

## Paired phone

The shipped script in `assets/phone.html` implements the paths and exact request
bodies in `application-api.md` and the backend's `web/ManagementApi.kt`. All calls
use the existing pairing token, bounded timeout and no-store client behavior.
Management data is explicitly loaded; none is added to the `/api/state` polling
payload, and memory is never automatically loaded or saved from observations.

Profile forms send a full `ProfileDraft`, preserving existing preferences and
restrictions outside the editable fields. Memory is always scoped by the selected
profile ID; paging includes the loaded revision. Export is an explicitly confirmed
GET, private readonly preview and JSON download; Hide discards the preview.
Delete, clear, switch, household recovery, routine deletion/restart and external
control require deliberate confirmation. Clearing includes `ownerConfirmed:true`;
profile deletion includes typed `deleteOwnedMemories:true` after its explicit scope
confirmation. Provenance is never supplied by the phone.

A form captures its revision when opened. Polls and list refreshes do not rebase
it or replace its draft. A 409 identifies conflict/busy and instructs the owner to
reload, review and reopen before deliberately reapplying an edit. Unsafe integer
revisions are refused instead of rounding a Long into a phone write. Management
requests have a shared in-flight lock that polling cannot release. Existing Ask,
approval, remote and settings locks remain independent, preserving Stop/approval
access while management is pending. HTTP 401/disconnect gates controls and
removes stale approvals and any displayed external credential. Browser values
and response strings are rendered using textContent or input values, never HTML.

Run/restart generates one UUID key per deliberate operation and retains it after
an unknown/refused response. Retry reuses that key; a successful acknowledgement
releases it so a later deliberate action gets a fresh key. The status and button
label expose this uncertainty. These keys live for the open page; after reloading
or leaving the TV page, inspect durable history before creating a fresh request.
The server retains idempotency only with the relevant history entry, as documented
by the domain contract.

The external status GET never contains a token. Only the enable POST response
fills the masked credential input, which can be revealed locally, discarded or
removed by revoke/disconnect. No polling, storage, request body or log receives it.

Phone CSS follows the good-css skill: existing semantic OKLCH tokens, logical
properties, intrinsic layout, bounded scroll areas, focus outlines, press feedback,
hover capability queries and content-sized bounded textareas. No library/version,
server, manifest or existing design primitive change was needed.

## Verification performed

- `node --test android/app/src/test/ui/phone.test.cjs`: **31 passing tests**. This
  runs the actual embedded shipped script with an in-memory DOM/HTTP boundary.
  All 12 prior tests remain. Added tests exercise explicit reads, safe rendering,
  exact typed/scoped profile and memory writes, preserved restrictions, stale
  revisions and drafts, revision-bound pagination, confirmations, private export,
  all schedule forms, numeric/offset rejection, unknown-key reuse/new-action keys,
  request locks across polls, history/cancel/restart, recovery, unsafe revisions,
  one-time credentials and discard on revoke/disconnect. The test DOM now parses
  heading tags with digits; production markup is unchanged by that harness fix.
- `ManagementFormsTest`: **5 passing JVM tests**, compiled with installed Kotlin
  2.4.21/JDK 21/JUnit 4.13.2 into a temporary directory. These exercise the actual
  TV parsing helpers: restrictive preference preservation, adult creation,
  zoned/offset schedule fields, invalid schedule/zone/interval rejection and
  request-key lifecycle.
- **Isolated Compose compilation passed** for the four new management sources,
  modified TvShell, existing TitleDialog and design sources. It used the installed
  Compose compiler/libraries, cached app/media dependencies and backend worker's
  compiled real OwnerManagement/domain classes. A temporary compile-only VelaApp
  boundary supplied exactly the documented public properties/methods; this is not
  whole-application linkage or APK proof. No shared build output was written.
- Owned-file `git diff --check` passed. API request/response shapes were checked
  against the implemented `ManagementApi.kt`, `ManagementProtocol.kt`, facade
  source and `application-api.md`; no mismatch was found at this handoff.

These checks do not establish live browser rendering, device focus/accessibility,
filesystem recovery, Android background timing, real AI/service behavior or an
end-to-end external MCP session. No screenshot or device result is claimed.
Compose focus behavior follows the installed lazy-container API documented by
[Android](https://developer.android.com/develop/ui/compose/touch-input/focus/focus-restoration).

## Coordinator acceptance still needed: 7 items

All seven are integration/QA work within the coordinator's authorized scope;
none is a request to change infrastructure or touch physical devices.

1. Once writers quiesce, run the combined Android tests and assemble, the separate
   Node UI suite and existing workspace checks. Verify VelaApp real linkage,
   route registration and the combined profile/routine execution policy.
2. On the root-owned emulator controller, capture new Profiles, both preference
   and memory editors, Routines/editor/history, External control, and the changed
   shell/Settings in **both themes**. Include loading/unavailable and long names.
   Verify D-pad entry/return, scroll restoration, deleting the focused item, Back,
   confirmations, visible focus overhang, increased font size and IME escape.
3. Use paired phone browser QA at 320/390/768 CSS pixels and 200% zoom, light/dark,
   keyboard navigation and touch. Verify browser confirmation, readonly credential
   selection/reveal, private export download/discard and responsive form scrolling.
4. Run TV/phone concurrently: save a stale profile/memory/routine edit and page
   memory across a revision change. Confirm 409, retained draft, deliberate review
   before reapplication, old-context invalidation and no cross-profile entry leak.
   Test startup/storage failure and explicit recovery without overwriting data.
5. Exercise real runtime ownership: switch while an agent/external/routine task is
   active; verify drain/cleared approvals/transcript, busy errors for competing
   writes, usable Stop/cancel/revoke, restrictive opaque-route denial and fresh
   discovery after the profile change. Existing restrictive policies must survive
   both TV and phone preference edits.
6. With an approved safe routine task, run all schedule types; verify durable
   reopen/history, queued/busy behavior, cancellation cleanup, restart, disabled/
   exhausted one-shots and a deliberately lost HTTP response reusing its key.
   Change/switch a bound profile and verify fail-closed behavior until edit/Save.
7. Verify default-disabled loopback MCP; explicitly enable from the owner surface,
   copy only the returned credential, check status contains none, and revoke during
   external work. Confirm a revoked credential is refused after drain. Use the
   existing emulator route; no LAN/public listener or new tunnel is authorized.
