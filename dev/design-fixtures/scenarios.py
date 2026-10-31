"""Synthetic DTOs only. No app assets, credentials, IO, or network clients."""
from uuid import UUID, uuid5

SCENARIOS = ("idle", "working", "pending", "error", "longcontent")
TOKEN = "PUBLIC-vela-design-fixture"
EXTERNAL_TOKEN = "PUBLIC-synthetic-external-token-NOT-A-CREDENTIAL"
NOW = 1793448000000  # Fixed 2026-10-31 fixture clock; no scheduling occurs.
NAMESPACE = UUID("2877837b-2775-49a1-93a7-4aa5127b1208")


def ident(name):
    return str(uuid5(NAMESPACE, name))


def preferences():
    return dict(preferredMediaProviders=["jellyfin"], preferredLibraries=[],
                audioLanguages=["en"], subtitleLanguages=["es"],
                subtitles="automatic", autoplayNext=False)


def restrictions():
    return dict(allowedMediaProviders=None, allowedLibraries=None,
                allowedAppPackages=None, maxAgeRating=None, allowUnrated=True,
                allowAgentActions=True, allowHomeControl=True)


def profile(name, child=False):
    limits = restrictions()
    if child:
        limits.update(allowedMediaProviders=["jellyfin"], allowedLibraries=[],
                      allowedAppPackages=[], maxAgeRating=12, allowUnrated=False,
                      allowHomeControl=False)
    return dict(id=ident(name), displayName=name, kind="child" if child else "adult",
                preferences=preferences(), restrictions=limits, createdAt=NOW, updatedAt=NOW)


def settings():
    return dict(provider="openai", baseUrl="http://127.0.0.1:1/synthetic-unused",
                apiKey="", model="SYNTHETIC fixture — no model connected",
                jellyfinUrl="", jellyfinToken="", jellyfinUserId="",
                homeAssistantUrl="", homeAssistantToken="", plexUrl="", plexToken="",
                ownHome=False, appearance="night", reduceMotion=False, highContrast=False)


def seed(scenario, generation):
    owner = profile("Synthetic Morgan")
    other = profile("Synthetic Alex")
    child = profile("Synthetic child", child=True)
    long_text = ("Synthetic long content: a quiet evening with captions, multilingual titles "
                 "日本語 · Español · العربية, and room to read every control. ") * 10
    profiles = [owner, other, child]
    if scenario == "longcontent":
        owner["displayName"] = "Synthetic Morgan with a deliberately long profile name"
    memories = [dict(id=ident(f"memory-{i}"), profileId=owner["id"],
                     kind="preference" if i % 2 == 0 else "fact",
                     text=(long_text if i == 0 and scenario == "longcontent"
                           else f"Synthetic memory {i + 1}: I enjoy captions on quiet evenings."),
                     source="owner_phone", tags=["synthetic", "captions"],
                     shareWithAgent=i % 2 == 0, createdAt=NOW, updatedAt=NOW + i)
                for i in range(25 if scenario == "longcontent" else 2)]
    # An inactive profile with a private memory exercises deletion confirmation/export scope.
    memories.append(dict(memories[0], id=ident("alex-memory"), profileId=other["id"],
                         text="Synthetic Alex private preference: short documentaries.", shareWithAgent=False))
    schedules = [dict(type="once", at=NOW + 86400000),
                 dict(type="interval", firstAt=NOW, everyMillis=3600000),
                 dict(type="weekly", days=[1, 3, 5], time="18:30", zone="America/Chicago"),
                 dict(type="monthly", day=31, time="20:00", zone="America/Chicago")]
    routines = [dict(id=ident(f"routine-{i}"), revision=generation * 10000 + 3,
                     spec=dict(name=f"Synthetic {s['type']} evening",
                               task=long_text if scenario == "longcontent" else "Synthetic task: suggest a quiet evening. No effects.",
                               schedule=s, enabled=i != 3, missedRunPolicy="RUN_LATEST",
                               maxLatenessMillis=86400000, ownerProfileId=owner["id"]),
                     createdAt=NOW, updatedAt=NOW, nextRunAt=NOW + 86400000)
                for i, s in enumerate(schedules)]
    history = []
    for i, state in enumerate(("SUCCEEDED", "FAILED", "CANCELED", "INTERRUPTED", "MISSED", "QUEUED")):
        r = routines[i % len(routines)]
        history.append(dict(id=ident(f"run-{generation}-{i}"), routineId=r["id"], revision=r["revision"],
                            name=r["spec"]["name"], task=r["spec"]["task"], source="MANUAL",
                            scheduledAt=NOW, queuedAt=NOW, expiresAt=NOW + 86400000,
                            state=state, startedAt=None if state in ("QUEUED", "MISSED") else NOW,
                            finishedAt=None if state == "QUEUED" else NOW + 1000,
                            detail="Synthetic history; no routine has executed.",
                            requestKey=f"synthetic-seed-{i}", restartedFrom=None, ownerProfileId=owner["id"]))
    tasks = [dict(id=ident(f"task-{generation}-{i}"), task=f"Synthetic {state} task",
                  state=state, startedAt=NOW + i, finishedAt=NOW + 1000,
                  detail="Synthetic history; no device action occurred.")
             for i, state in enumerate(("complete", "failed", "canceled"))]
    lines = [dict(kind="you", text="Synthetic request: suggest something gentle for tonight."),
             dict(kind="step", text="Synthetic fixture: no provider or device is connected."),
             dict(kind="said", text="Synthetic reply: a short documentary and captions might suit tonight.")]
    running = scenario in ("working", "pending")
    if running:
        tasks.append(dict(id=ident(f"active-{generation}"), task="Synthetic task waiting for fixture control",
                          state="running", startedAt=NOW + 2000, finishedAt=None,
                          detail="Synthetic work stays pending until Stop or an exact approval answer."))
        lines.append(dict(kind="step", text=tasks[-1]["detail"]))
    if scenario == "error":
        lines.append(dict(kind="error", text="Synthetic failure: model unavailable. No action was attempted."))
    if scenario == "longcontent":
        lines.extend([dict(kind="you", text=long_text), dict(kind="said", text=long_text),
                      dict(kind="step", text="Synthetic unbroken label: " + "longcontent" * 30),
                      dict(kind="error", text="Synthetic literal text <script>not executable</script> & \"quotes\".")])
        tasks[-1].update(task=long_text, detail=long_text[:1000])
    state = dict(settings=settings(), agentReady=True, running=running,
                 pending="Synthetic approval: allow the fixture lamp request? This changes RAM only; no home/device is connected." if scenario == "pending" else None,
                 pendingId=f"synthetic-phone-{generation}" if scenario == "pending" else None,
                 lines=lines, tasks=tasks,
                 device=dict(version="SYNTHETIC fixture", androidApi=37, model="SYNTHETIC phone fixture — no TV connected",
                             surfaceWidth=1920, surfaceHeight=1080, agentConfigured=True,
                             accessibility=True, mediaAccess=True, nativeDpad=True,
                             jellyfinConfigured=False, homeConfigured=False, plexConfigured=False,
                             runtime=dict(householdError=None, routineError=None, phoneError=None, externalError=None)))
    layout = dict(version=1, rows=[dict(id="synthetic-apps", title="Synthetic apps", style="app", source=dict(type="apps", packages=None)),
                                   dict(id="synthetic-watchlist", title="Synthetic watchlist", style="wide", source=dict(type="watchlist"))])
    return state, profiles, memories, routines, history, layout
