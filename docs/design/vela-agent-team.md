# Requested GPT 6.1 Sol fleet

Status: not started. The 2026-10-31 session discovered a disabled T3 MCP server in the global Codex configuration.
That flag has been enabled, but the active provider must reload its tool catalog before delegation and secure credential
requests can run. Do not substitute a different model or start background CLI agents.

Use T3 `orchestrator_capabilities` to resolve the exact GPT 6.1 Sol catalog entry and `request_secret` for the expiring
API credit. Confirm which provider instance owns that credit; the current session is not evidence that the credit is being billed.
The reported $1,000 credit is an upper limit, not an instruction to waste it. The reported 21-hour expiry must be rechecked.

The implementation inventory is in [vela-tv-system.md](vela-tv-system.md). Preserve the existing worktree and uncommitted
edits, avoid concurrent edits to owned files, and keep the pivot unmerged for owner review.

Suggested first wave, after the coordinator commits the current baseline:

| Sol assignment | Owned scope | Expected output |
| --- | --- | --- |
| Design and focus review | Read-only Android UI and companion review | Concrete findings with reproduction / device evidence; no edits |
| Agent correctness/security review | Read-only agent, guard, settings, HTTP | Ranked findings and regression cases; no edits |
| Media completion | `media/`, `player/`, relevant media tests | Negotiation/tracks/episode pagination, tested; coordinate TileAction changes |
| Household and preferences | New profile/memory modules and tests | Explicit schema/migration, inspect/edit/delete APIs; no shared shell edits |
| Durable routines | New routines module and tests | Persistent schedules, restart semantics and execution-time guard contract |
| Integration architecture | New adapter/MCP modules and tests | Supported integration seams and authenticated external-control contract |

Give workers the exact repo path, base SHA, allowed files and output format. Workers are leaves; commits/merges,
credentials, physical devices and infrastructure remain with the coordinator. A second wave integrates modules into
TV/phone surfaces after their contracts are reviewed. Use fresh delegates for every review round.

No worker may flash hardware, touch the owner's TVs, modify external infrastructure or merge the pivot on the basis
of the broad development request. Local builds and emulator work are allowed subject to the session's device-tool rules.
