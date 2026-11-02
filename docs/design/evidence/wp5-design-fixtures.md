# WP5 phone fixture implementation evidence

Scope: only new `dev/design-fixtures/**` and this note. Read both governing design
contracts in full, current native `design/**` APIs (including corrected activation
gate), phone server/routes/DTOs, application API agreement and phone test fixtures.
No production code, asset, contract, Git refs/index, shared build output, firmware,
infrastructure, credentials or physical Shield was modified by this worker.

Implementation and exact launch/scenario/reset/conflict/fault/release commands:
[dev/design-fixtures/README.md](../../../dev/design-fixtures/README.md).

- The loopback-only Python stdlib server serves current `phone.html` bytes fresh
  on each page request. No alternate UI or script/style injection.
- Five connected synthetic scenarios cover idle/working/pending/error/longcontent;
  every management surface has current route-shaped in-memory data and mutations.
- Approval IDs are server-instance/reset-specific; stale/replayed IDs conflict.
  Household/routine revisions, atomic write conflicts, private memory scope and
  manual-run/restart request-key identity are exercised over actual loopback HTTP.
- All fixture operations are RAM-only. External control never opens a listener,
  remote keys are no-ops, and connections are never contacted. The optional model
  endpoint was omitted to keep the phone fixture bounded.

Verification performed (Python 3.15.2 installed here; source targets 3.10+):

```sh
python3 -B -m unittest discover -s dev/design-fixtures -p 'test_*.py' -v
```

Initial execution: 22 passed, one failed because Python's HTTP parser normalized
a leading `//` before route validation. Fixed by comparing the original request
target, added cross-operation routine-key checks, then reran: **23 passed**.
The lifecycle test explicitly starts the CLI on a free loopback port, sends
SIGTERM, observes exit 0 and the release message, and verifies the port rejects
a new connection. Every test fixture listener is closed in teardown.

Final revalidation after instance-specific prompt IDs and monotonic reset revisions:

- Same unittest command: **25 tests passed in 1.160s**, exit 0. Added regressions
  cover cross-server prompt separation and reset after counters exceed a seed epoch.
- `ast.parse(..., feature_version=(3, 10))` over all five fixture Python files:
  passed. This checks Python 3.10 syntax, not execution on a Python 3.10 interpreter;
  runtime verification used the installed Python 3.15.2.
- Owned-file trailing-whitespace scan: no findings. `git diff --check`: exit 0.
  The explicit scan includes new untracked files that Git's diff omits.
- `pgrep -fl '[p]ython.*dev/design-fixtures/fixture.py'`: exit 1, no matches.
  **No fixture server left running.**

Pending coordinator checks (three groups):

1. Browser visual/interaction QA of the actual phone page at 320/390/768/1280 CSS
   widths, both themes and 200% zoom where available, including focus, busy/stop,
   approval, long content, management editors, conflicts and reconnect handling.
2. Combined repository validation after all writers stop: the Android Gradle
   matrix and Node phone suite specified by the governing contract; PR6 delivery.
3. Native TV/device visual acceptance remains pending under the owner's physical
   Shield pause. This fixture supplies no native-device evidence.

No screenshot or UI acceptance is claimed. No server is intentionally left running.
