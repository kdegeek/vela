# Application integration agreement

2026-10-31, coordinator-owned contract for parallel backend/UI assembly. See household, routines, external-control and core-execution contracts for domain semantics.

VelaApp public seams shared with core and management UI:

- `val control: ControlArbiter`, `val externalApprovals: ControlApprovalBroker`, initialized before Runner.
- `suspend fun householdStore(): HouseholdStore`; `fun householdOrNull(): HouseholdStore?`; `val householdReady: StateFlow<HouseholdStore?>`.
  Null means startup/failure and must fail closed for actions; UI shows loading or initialization error.
- `suspend fun routineEngine(): RoutineEngine`, returning the one process-wide engine; implement RoutineRuntimeOwner.
- `var mcpService: McpService?` with private setter; null while external control is disabled. Core Stop cancels its work.
- Backend assembler may add a typed owner-management facade for TV and phone to share profile-switch/invalidation rules.
  Publish its exact API here before integration handoff. No management UI bypasses active-controller exclusion or transcript invalidation.

Paired phone routes (all under the existing X-Vela-Token boundary, bounded bodies, no-store):

| Method/path | Request / response contract |
| --- | --- |
| GET /api/profiles | `{revision,activeProfileId,profiles}`; never include memory in this polled/list response |
| POST /api/profiles | `{revision,profile:ProfileDraft}` -> revision and saved profile |
| PUT /api/profiles/{id} | Same full draft, current expected revision |
| POST /api/profiles/active | `{revision,profileId}`; stop/join and clear prior profile context before switching |
| DELETE /api/profiles/{id} | `{revision,deleteOwnedMemories}`; explicit confirmation, inactive/last safeguards |
| GET /api/profiles/{id}/memories | offset/limit/expectedRevision query; bounded MemoryPage |
| POST /api/profiles/{id}/memories | `{revision,kind,text,tags,shareWithAgent}`; explicit Save sets trusted PHONE provenance |
| PUT /api/profiles/{id}/memories/{memoryId} | Same editable fields |
| DELETE /api/profiles/{id}/memories/{memoryId} | `{revision}` |
| POST /api/profiles/{id}/memories/clear | `{revision,ownerConfirmed:true}` |
| GET /api/profiles/{id}/export | Explicit owner export, not a model tool |
| GET /api/routines | RoutineSnapshot definitions/history |
| POST /api/routines | `{spec:RoutineSpec}` |
| PUT /api/routines/{id} | `{revision,spec:RoutineSpec}` |
| DELETE /api/routines/{id} | `{revision}` |
| POST /api/routines/{id}/enabled | `{revision,enabled}` |
| POST /api/routines/{id}/run | `{requestKey}`; one fresh key per deliberate action, reused only for transport retry |
| POST /api/routine-runs/{runId}/cancel | `{}` |
| POST /api/routine-runs/{runId}/restart | `{requestKey}` |
| GET /api/external-control | Enabled, loopback address and credential-present status; no token |
| POST /api/external-control/enable | `{ownerConfirmed:true}`; generate dedicated credential only after explicit local action; return it once |
| POST /api/external-control/revoke | Disable, cancel external work and drain control before admitting new input |

Use exact integer revisions, typed booleans, sanitized domain errors (400 validation, 404 missing, 409 conflict/busy, 503 storage/startup).
Connection/configuration writes and direct remote/layout actions participate in shared controller ownership. Stop and approval answers remain usable while a lease is held.

New MCP listener binds only 127.0.0.1:8766, default disabled; reuse ToolExecutor prepared-target semantics rather than comparing GuardHit twice. No internal model is required for external control. Scope credentials to this feature, exclude backup, support explicit revocation. A LAN/gateway/public deployment is not authorized by implementing this local flow.

Profiles are personalization scopes, not authenticated Android users. Do not claim secure parental authentication or OS-wide restrictions. Restrictive/child profiles fail closed for opaque routes whose content cannot be verified. Household-global layout/watchlist stays global unless an explicit migration is designed and tested.

## OwnerManagement API (backend implementation contract)

`VelaApp.management: app.vela.tv.runtime.OwnerManagement` is the common TV/phone
facade. All methods below are suspending. Household/configuration mutations reject a competing controller
with `ControlBusyException`; switching and household recovery explicitly stop and
join existing work before taking the management lease. Memory/profile writes clear
agent transcript context before releasing that lease. UI must call this facade,
not mutate `householdStore()` / `routineEngine()` directly.

```kotlin
val busy: StateFlow<String?> // same as app.control.owner
suspend fun stopAndJoin()
suspend fun profiles(): ProfileList // revision, activeProfileId, profiles; no memory
suspend fun createProfile(revision: Long, draft: ProfileDraft): HouseholdCommit<HouseholdProfile>
suspend fun editProfile(revision: Long, profileId: String, draft: ProfileDraft): HouseholdCommit<HouseholdProfile>
suspend fun switchProfile(revision: Long, profileId: String): HouseholdCommit<HouseholdProfile>
suspend fun deleteProfile(revision: Long, profileId: String, deleteOwnedMemories: Boolean): HouseholdCommit<String>
suspend fun memories(profileId: String, offset: Int = 0, limit: Int = 50, expectedRevision: Long? = null): MemoryPage
suspend fun saveMemory(revision: Long, profileId: String, draft: MemoryDraft,
    memoryId: String? = null, source: OwnerMemorySource = OwnerMemorySource.TV): HouseholdCommit<MemoryEntry>
suspend fun deleteMemory(revision: Long, profileId: String, memoryId: String): HouseholdCommit<String>
suspend fun clearMemories(revision: Long, profileId: String, ownerConfirmed: Boolean): HouseholdCommit<Int>
suspend fun exportProfile(profileId: String): String
suspend fun reloadHousehold(): ProfileList
suspend fun routines(): RoutineSnapshot
suspend fun createRoutine(spec: RoutineSpec): Routine
suspend fun updateRoutine(id: String, revision: Long, spec: RoutineSpec): Routine
suspend fun deleteRoutine(id: String, revision: Long)
suspend fun enableRoutine(id: String, revision: Long, enabled: Boolean): Routine
suspend fun runRoutine(id: String, requestKey: String): RoutineRun
suspend fun cancelRoutineRun(runId: String): RoutineRun
suspend fun restartRoutineRun(runId: String, requestKey: String): RoutineRun
suspend fun externalControl(): ExternalControlStatus
suspend fun enableExternalControl(ownerConfirmed: Boolean): ExternalControlProvision
suspend fun revokeExternalControl(): ExternalControlStatus
```

Types in `runtime`: `@Serializable ProfileList(revision:Long,activeProfileId:String,
profiles:List<HouseholdProfile>)`; `@Serializable MemoryDraft(kind:MemoryKind,text:String,
tags:List<String> = emptyList(),shareWithAgent:Boolean = true)`.
Types in `mcp`: `@Serializable ExternalControlStatus(enabled:Boolean,address:String,
credentialPresent:Boolean,error:String? = null)`; `ExternalControlProvision(status:
ExternalControlStatus,token:String)` (redacted `toString`, returned once by enable).

Routine create/update binds the definition to the current active profile and its
exact saved policy/preferences snapshot, stored privately in a durable sidecar.
Execution fails closed if the binding is missing, stale, the profile changed, or
another profile is active. Edit/save the routine deliberately to rebind it; enabling
or running does not silently rebind. Run history stays in the domain engine.

Additional paired routes: `GET /api/apps` returns `{apps:[{packageName,label}]}`
from installed launchable apps only, for owner allowlist choices; it launches
nothing. `POST /api/household/reload` with `{}` reconciles household storage after
an ambiguous write. Startup/storage messages appear in `DeviceStatus` and TV can
observe `VelaApp.runtimeStatus: StateFlow<RuntimeStatus>` (`householdError`,
`routineError`, `phoneError`, `externalError`, all nullable safe strings).

Routine definition/history edits serialize with owner management but do not take a screen-control lease. Run-now can persist a queue entry while another owner is active; disable/delete/cancel/restart cancel only that routine. Actual dispatch still atomically reserves Runner. Household write responses use `{revision,value}`; routine responses are the saved `Routine`/`RoutineRun` directly.

Routine ownership is now also visible and durable in `RoutineSpec.ownerProfileId`,
`RoutineRun.ownerProfileId`, and `RoutineTaskRequest.ownerProfileId` (nullable only
for legacy v1 data). The owner facade overwrites any caller-supplied owner with the
active profile on create/edit; phone write schemas reject ownerProfileId. Old v1
files decode with null and the application pauses those definitions at startup,
canceling their queued work. Only an explicit definition edit reassigns ownership.
The policy snapshot sidecar remains necessary to prevent running saved requests
after their original profile's permissions/preferences changed.

## Backend integration verification (2026-10-31)

Mounted by VelaApp/PhoneServer. Isolated Kotlin 2.4.21/JDK 21 compilation and
JUnit passed **82 tests**, including all existing **33 routine tests**, existing
MCP transport/auth tests, prepared-target invalidation, external lifecycle and
credential revoke/drain, paired management routes, strict/stale/malformed writes,
sanitized storage errors, trusted routine ownership, cross-profile reservation
rejection, legacy pause, and exact-task cancellation cleanup. The actual owned
Android application sources also compile against SDK 37 and compiled peer APIs.
No combined Gradle build or emulator/device work was performed by this worker.

A routine definition and its policy sidecar are separate verified atomic files.
An interrupted two-file save fails closed through revision mismatch; edit/save the
routine from its intended profile to repair that binding. No partial binding may
grant permissions. On ambiguous credential writes, status retains credential-present
and requires revoke before reprovisioning. Revoke immediately removes authorization,
then cancels and joins service-owned work before listener/storage cleanup.
