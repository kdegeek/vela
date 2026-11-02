# Synthetic phone design fixtures

Python **3.10+ standard library only**. This serves the **current, unmodified**
`android/app/src/main/assets/phone.html` plus route-shaped synthetic responses.
The file is read fresh on every `GET /`, including while WP3 edits it. There is
no alternate page, DOM/CSS/JS injection, proxy, arbitrary file server, browser
automation, Android broker, provider, device control, or external HTTP client.

Run these commands from the repository root. The fixed token is **PUBLIC test
data**, not a real pairing credential. Use synthetic input only.

## Launch and release

```sh
python3 -B dev/design-fixtures/fixture.py --port 8875 --scenario idle
```

For the coordinator's browser QA, open the printed URL:

```text
http://127.0.0.1:8875/#t=PUBLIC-vela-design-fixture
```

Only `127.0.0.1` is bound; there is no `--host` option. `--port 0` selects a free
port and prints the resulting URL. If 8875 is occupied, choose another port;
do not stop the process occupying it. Use the same port in the commands below.

**Release:** press **Ctrl-C** in that terminal. The process closes its listener,
discards all RAM state, prints `Synthetic fixture released.`, and exits.

For a shell-controlled run, keep the PID in that same shell and stop only it:

```sh
python3 -B dev/design-fixtures/fixture.py --port 8875 --scenario idle &
vela_fixture_pid=$!
# Perform this fixture's QA, then release it:
kill -TERM "$vela_fixture_pid"
wait "$vela_fixture_pid"
unset vela_fixture_pid
```

No automatic browser opens and no background service is registered. Do not leave
the process running after QA. No physical Shield actions are needed or permitted
by this fixture assignment; the owner's pause remains in effect.

## Scenarios and reset

Every API/control request requires `X-Vela-Token: PUBLIC-vela-design-fixture`.
All state is per server instance. GET requests never advance a scenario.

```sh
curl --fail-with-body -sS http://127.0.0.1:8875/__fixture/state \
  -H 'X-Vela-Token: PUBLIC-vela-design-fixture'

# Choose idle, working, pending, error, or longcontent:
curl --fail-with-body -sS http://127.0.0.1:8875/__fixture/scenario \
  -H 'X-Vela-Token: PUBLIC-vela-design-fixture' \
  -H 'Content-Type: application/json' --data '{"scenario":"pending"}'

# Discard all edits, reset the current scenario, clear faults, expire old prompts:
curl --fail-with-body -sS http://127.0.0.1:8875/__fixture/reset \
  -H 'X-Vela-Token: PUBLIC-vela-design-fixture' \
  -H 'Content-Type: application/json' --data '{}'
```

Scenario selection also resets all RAM data. Reset increments household/routine
revisions so open stale editors conflict. Reload the page after selecting a
scenario/reset to repopulate connection fields and discard intentional unsaved
editor state; its ordinary polling deliberately preserves open drafts. Management
data is loaded by the real page's explicit load buttons, never injected on load.

| Scenario | Observable fixture data |
| --- | --- |
| `idle` | Connected state, synthetic conversation, complete/failed/canceled task history; remote accepts valid keys as no-ops. |
| `working` | Running task and step, busy conflicts for remote/settings/layout/profile/memory changes. Stop remains usable. |
| `pending` | Working plus a question and exact `pendingId`. Allow/Decline resolve RAM state once; replay/wrong IDs return 409. No real approval or device action occurs. |
| `error` | Still connected, synthetic error transcript and failed history; Test connections returns synthetic failures without making connections. |
| `longcontent` | Long multilingual text, an unbroken label and literal markup as text; long profile name/task; 25 memories (20 + 5 through the real UI), including private entries. |

Asking through the real page starts a synthetic running task which stays working
until Stop. It is not an AI response. Pending approval is selected explicitly via
the scenario command, not inferred from task text. Phone fixture approval is
**entirely separate from the Android approval broker**.

## Management surfaces

All scenarios include three synthetic profiles (adult, another adult, restrictive
child), full preference/restriction DTOs, shared/private explicit memory, four
routine schedule types, durable-shaped run history, synthetic app allowlist
choices, layout, and disabled external-control status. The page's buttons exercise:

- Profile create/edit/switch/delete with household revisions, active-profile
  protection, owned-memory confirmation, context invalidation and explicit reload.
- Memory pagination, exact-word create/edit/delete/clear, trusted `owner_phone`
  source, and export restricted to the selected profile (including private entries).
- Routine create/edit/enable/delete, definition revisions, request-key retry
  identity, queued run/cancel/restart, retained history, and synthetic stale-profile
  failure. Owner IDs come from the active profile, never the write payload.
- External enable/revoke and a one-response **PUBLIC synthetic token**. Enabling
  changes RAM only: it never starts an MCP listener or provisions any credential.
  Status and `/api/state` never contain that synthetic external token.
- Settings edits (synthetic credentials only, returned masked), layout roundtrips,
  connection-test responses and valid remote key no-ops. No supplied address or
  layout link is fetched by the server.

## Deliberate conflicts and reconnect states

To verify an open profile/memory draft remains intact after a stale write:

```sh
curl --fail-with-body -sS http://127.0.0.1:8875/__fixture/conflict \
  -H 'X-Vela-Token: PUBLIC-vela-design-fixture' \
  -H 'Content-Type: application/json' --data '{"target":"household"}'
```

Save the previously opened draft; it should receive 409. Reloading lists does not
change that draft's captured revision. Close/reopen the editor to use fresh data.

For a routine, obtain its ID from `GET /api/routines`, open its editor, then:

```sh
curl --fail-with-body -sS http://127.0.0.1:8875/__fixture/conflict \
  -H 'X-Vela-Token: PUBLIC-vela-design-fixture' \
  -H 'Content-Type: application/json' \
  --data '{"target":"routine","id":"REPLACE_WITH_SYNTHETIC_ROUTINE_ID"}'
```

To exercise disconnect/expired-pairing handling without stopping the server:

```sh
# Use 503 for disconnected, or 401 for expired pairing:
curl --fail-with-body -sS http://127.0.0.1:8875/__fixture/poll-fault \
  -H 'X-Vela-Token: PUBLIC-vela-design-fixture' \
  -H 'Content-Type: application/json' --data '{"status":503}'

# Reconnect; the PUBLIC fixture token stays valid:
curl --fail-with-body -sS http://127.0.0.1:8875/__fixture/poll-fault \
  -H 'X-Vela-Token: PUBLIC-vela-design-fixture' \
  -H 'Content-Type: application/json' --data '{"status":null}'
```

These faults affect only `/api/state`. Control commands remain authenticated and
available. The next normal phone poll (or Reconnect) observes recovery.

## Boundaries and limitations

The owning current routes are `web/PhoneApi.kt` and `web/ManagementApi.kt`;
`web/OwnerRoutes.kt` does not exist in this checkout. DTOs were read from
`PhoneServer.kt`, `DeviceStatus.kt`, `Settings.kt`, `HouseholdModels.kt`,
`ExplicitMemory.kt`, `RoutineModels.kt`, `OwnerManagement.kt`,
`docs/design/application-api.md`, and current phone test shapes.

This is a UI fixture, not a Kotlin domain-engine reimplementation. Intentional
limits: fixed synthetic timestamps; no time progression, DST/calendar dispatch,
routine execution, persistence, storage-failure recovery, real credential merge,
full sensitive-memory policy, provider/network tests, external controller, or
actual Android approval/control lease. Newly saved routines have `nextRunAt:null`
(no scheduler); seeded next-run values are synthetic. Seeded queued runs stay
queued until canceled/restarted/reset. Stale-profile execution is represented by
an immediately failed synthetic run. Settings updates patch RAM fields. Fixture
validation is deliberately stricter in some cases than the app (unknown fields,
exact JS-safe integers, synthetic-only credentials). It is not proof of backend
guard correctness or visual acceptance.

Limits: 262,144-byte bodies, 2,048-character request targets, JSON depth 24,
8 simultaneous connections, 3-second socket read timeout, 8 profiles, 100 memories
per profile, 32 routines and 100 run-history records (reset when full). Household
and routine writes serialize with revision checks. Bodies require explicit
Content-Length when nonempty; **chunked/compressed bodies are refused**. This
differs from production's bounded chunked support. Duplicate tokens, JSON keys,
ambiguous framing, cross-origin requests, non-loopback Host values, traversal and
encoded paths are refused. There is no CORS permission. The public token only
separates deliberate local fixture use; it is not a secret or user authentication.

No optional `/v1/chat/completions` was added. Existing `dev/tv-fixtures` is untouched.

## Verify without browser/device/build work

```sh
python3 -B -m unittest discover -s dev/design-fixtures -p 'test_*.py' -v
```

Tests bind ephemeral loopback ports, use real HTTP, and close every listener.
They verify actual asset byte equality and fresh reads, auth/Host/Origin and
framing bounds, malformed JSON, all scenarios, busy/stop/clear, approval identity
and races, household revision races, memory isolation/paging/export, CRUD,
routine retry operation identity/cancel/restart, external token visibility,
fault/reset recovery, and subprocess SIGTERM release. They perform no visual QA.
