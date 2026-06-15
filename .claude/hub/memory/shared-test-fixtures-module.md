---
name: shared-test-fixtures-module
description: "Cross-module test fixtures live in a dedicated *-testkit module (src/main/java, scope=test) — never duplicated, never a test-jar (m2e doesn't resolve test-jar classpaths)"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 4d3d8a2e-f292-4cbe-a699-fb4abfbd1e6c
---

Convention the user set (2026-06-09) for sharing test fixtures/helpers across Maven modules in this
repo. **Why:** the user objected hard to my mention of copying a fixture between modules ("we should
never duplicate code, even in the tests, where we can reuse the logic using the test classpath").

**The rule:**
- Fixture shared WITHIN one module → keep it in that module's `src/test/java` (a package-private
  helper). No extra module — `src/test` already sees `src/test` inside a module.
- Fixture shared ACROSS modules → a dedicated `*-testkit` module, fixtures in **`src/main/java`**,
  consumed by each module in **`scope=test`**. NEVER copy, NEVER a Maven test-jar.

**Why test-jar is rejected (the user's specific knowledge):** the one-module-two-artifacts route
(maven-jar-plugin `test-jar` goal + `<type>test-jar>` dependency) is Maven's "official" answer, but
**m2e (m2eclipse) does not put the test-jar on the consumer's test classpath** — the IDE stays red on
the shared fixture. The user lives in the IDE, so that is disqualifying. A plain **main** artifact of
a workspace project resolves cleanly in BOTH CLI Maven and m2e; `scope=test` keeps it out of the prod
runtime. So: main-source fixture in a dedicated module, test-scoped dependency.

**The trigger is CROSS-MODULE, not "it's a fixture"** — don't spawn a testkit for a single in-module
use. And apply the rule-of-three: extract only when there are real consumers (here: `StackHandleTest`
in pulumi-automation-ext + seed-master's SnapshotSource/Task-12 test → two real consumers, justified).

**First instance (this session):** module `pulumi-automation-ext-testkit` holding `StackHistoryFixture`
(writes the file-backend `.pulumi/history/<project>/<stack>/<stack>-<ts>.{history,checkpoint}.json`
pair), extracted from `StackHandleTest`'s former private `writeHistoryPair`/`prepareHistoryDir`
helpers. Relates to [[rke2lab:medical-record-query-api-state]], [[rke2lab:build-verification-gotchas]],
[[sequential-no-compat-workflow]] (delete the old private helpers in the same change, no compat).
