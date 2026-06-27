---
name: world-exchange-2a-execution-state
description: World-exchange 2A SHIPPED on feature/cluster-edge (2026-06-27) — readiness verdict crosses as a Document, ControlplanePolicy doctor-free, a Plan-1 gate cold-tree deadlock fixed, and the in-container proxy tests refactored to DERIVE their install closure from the host. 2B is SPECCED + PLANNED + COMMITTED (4c91a852), ready to EXECUTE subagent-driven — the consult path crosses as a Document, by zone (shared seam first), see the 2B RESUME section at the bottom.
metadata:
  type: project
---

## 2A SHIPPED (2026-06-27, feature/cluster-edge — kept, not merged)

Plan: `wip/plans/2026-06-27-world-exchange-2a-document-foundation.md`.
Spec: `docs/architecture/osgi/world-exchange-2a-document-foundation-spec.adoc`.
Parent design: [[world-exchange-document-design]] (2A/2B/2C/2D; 2A = Document foundation +
readiness verdict crossing; cut = parse-vs-consume so `from()` is doctor-free).

Commits (on top of `b423f407` Task 3):
- `0269c1b0` **fix(staging)** — Plan-1 gate timing flaw. `REALM_BOUNDARY` ran inside
  `reconfigureStaging` BEFORE `delegate().execute()` (before compile) but reads the exec's
  `target/classes` → on a cold tree: no governance anchor → ERROR default → only seam dep-jars
  policed → 18 ERROR → build fails at generate-resources → compile never runs → DEADLOCK. FIX:
  `enforceGates` moved AFTER `delegate().execute()`; the shade/staging Xpp3Dom reconfiguration STAYS
  before (mojos read it as they build). `resolveBundles` computed once, shared. The gate now runs on
  EVERY build and finally self-scans host classes on clean builds (soundness gap closed). Extension
  is RELEASE-coord `1.0.0` via `.mvn/extensions.xml`, so it must be `install`ed to ~/.m2 (the
  documented exception — the reactor can't supply an extension loaded before it).
- `abe3626e` **feat(seed) Étape 4** — `SystemdAdapterStage` builds a readiness-checkpoint `Document`,
  calls `ReadinessAuthority.assess`, reads the verdict's `action` (stop|continue-degraded). No
  `Severity` type on the host anymore; Task-3 bridges gone. `runbook`/`consultations`/`doctor` are
  `@Nullable` (the stage null-guards them — real optional collaborators; prod ALWAYS supplies them,
  null only in test). A package-private 7-arg test-only ctor omits the three; the same-package test
  fixture bridges it to a public `failing(...)` factory — keeps the test ctor off the prod public
  API WITHOUT dropping the class's `final` (so no anonymous-subclass/inheritance route). worklist
  44→41.
- `fe31317a` **refactor(testkit)** — see the durable pattern below.

Verified: full reactor `package -Pall-worlds -DskipTests=false` BUILD SUCCESS, 0 test failures,
`realm-boundary: 0 error` everywhere (41 warn for seed-master). Reviewed by an opus code-reviewer
(only minor comment-hygiene findings, all fixed and folded in).

## Durable pattern learned: DERIVE the in-container install closure from the host

The `OutOfContainerFrameworkExtension` proxy tests each hand-maintained the bundle set to install
(the JUnit runner world ×3, jackson, doctor records+spi, manifests' 14-name GRAPH). Three were
derivable. Generalized the SHARED `BundleIndex.closeOverImports` walk — the one prod `BootPlanner`
drives, already used here for `withScr()` — to seed from the HOST bundle:

- `installImportClosureOf(Bundle... hosts)` installs every classpath bundle the hosts transitively
  import, nothing else. Its already-provided set is read from the **running system bundle's own
  exports** (`framework.adapt(BundleWiring).getCapabilities(PACKAGE_NAMESPACE)`) — intrinsic
  framework packages + the seam `systemPackages`. Seeding it with only the configured seams was the
  bug that pulled `osgi.core` (a duplicate exporter of `org.osgi.framework` → resolve returns false
  though everything "wired"). `exporterOf` already skips seam-typed bundles, so a seam is never
  pulled.
- `withJUnitRunner()` captures the proxy-infra runner world (launcher/engine/this testkit) in ONE
  shared declaration — it is the test's own scaffolding, NOT derivable from the host.

What STAYS explicit is exactly what OSGi makes irreducible: **seams a host imports stay
system-exported** (host-flat by design; the walk skips them) — and a seam exports MULTIPLE packages,
so list them ALL (manifests-core needed manifests.port + .port.node + .port.profiles + netplan.port +
systemd.port + pipeline; missing one → host UNRESOLVED). A FRAGMENT is reached only if it EXPORTS a
package the host imports (manifests' systemd-cdk8s-manifests exports systemd.cdk8s → pulled); a
fragment nothing imports could not be derived (doctor-port seeds host+fragment because the fragment's
FakeSpecialist imports doctor.spi the host doesn't). Migrated all 3 proxies uniformly: doctor-core 30,
doctor-port 34, manifests-core 6 — green. Diagnostic lever for a false resolve: `@FrameworkLog(DEBUG)`
prints the Felix resolver WIRE/FRAGMENT-WIRE trace to stdout (no slf4j backend needed); the
`resolve()` slf4j post-mortem needs a backend (JGivenTestkit supplies one, bare `builder()` does not).

## 2B — SPECCED + PLANNED, RESUME HERE (execute, 2026-06-27)

Spec: `docs/architecture/osgi/world-exchange-2b-consult-path-spec.adoc`.
Plan: `wip/plans/2026-06-27-world-exchange-2b-consult-path.md` (7 tasks, TDD, one commit each).
Both committed `4c91a852`. Design brainstormed WITH the user (5 decisions, all in the spec) — do NOT
re-litigate; execute.

**Scope:** the consult/failure path crosses as a Document, decomposed BY ZONE (user's choice), the
shared seam first because both consult stages share `ConsultingService`'s 3 verbs:
- zone-0 (Tasks 1-3): add `consult(Document checkpoint)→Document consultation` to `ConsultingService`
  (the Document twin of 2A's `assess`, a DISTINCT verb — NOT folded into assess); `Generalist`
  implements it, rendering the narration string AND the `diagnosisAdoc` AsciiDoc block OSGi-side (it
  owns the `RemediationPlan`); rename `DoctorGraph`→`ConsultationDag`.
- zone-1 (Task 4): systemd-adapter — probe returns a checkpoint Document (symptom as a SLUG string,
  no `Observation.failed(Symptom)`), stage calls `consult(checkpoint)`, logs narration.
- zone-2 (Task 5): cluster — identical; THEN remove the 3 old record-typed verbs from the seam.
- Task 6: `RunbookRenderer` reads `consultation.diagnosisAdoc()` (a string) into the jGiven shell,
  drops its `doctor.records` imports; `diagnosisBlock` MOVED to `Generalist` in Task 2.
- Task 7: close-out — worklist's consult-path slice gone, mark 2B shipped.

**The 5 design decisions (settled, in the spec):** (1) consult DISTINCT from assess — keeps the
authority(verdict) vs consulting(diagnosis) seam split. (2) narration + diagnosisAdoc are strings the
host LOGS/INSERTS — DEFINITIVE, not transitory; produced OSGi-side. (3) the host does NOT render the
runbook — OSGi produces the AsciiDoc TEXT (markup, not HTML), so NO asciidoctor/jruby/graphviz
dependency. (4) DoctorGraph→ConsultationDag. (5) self-review caught: 2A's checkpoint
(scenarioId/failed/override) is INSUFFICIENT to route a consult — EXTEND it with `symptomKind` (the
Symptom slug, OSGi maps back to the enum it owns) + `summary` + `details`; one checkpoint instance
feeds both assess and consult.

**Two open verifications flagged for the executor** (in the plan's self-review): whether `Checkpoint`
is a `doctor.records` type (then the runbook join uses the raw slug string instead) and whether
`doctor-port` already deps `exchange-port` (add if absent — Task 1).

**Boundaries:** 2B touches ONLY the consult/failure path. NOT the reconstruction path (`DriftReview`,
`*Reader`, `recordForCurrentPatient`/`reviewOpenProblems` — those 2 seam verbs STAY) = 2C; NOT the
Pulumi-resource egress (`*Resource`, `toOutputMap`) = egress increment; NOT the JSON schemas / the
REALM_BOUNDARY→ERROR flip = 2D. `ConsultationReport` is NOT deleted (reconstruction + its OSGi tests
still use it). Green-per-zone: zone-0 is build+OSGi-test green but the HOST worklist does NOT shrink
until zone-1/2 (host still calls old verbs) — expected, not a regression.

**Verify recipe:** seed-master via `package -Pall-worlds -DskipTests=false -Dmaven.build.cache.skipCache=true`
(NEVER bare `test`); doctor-core-test via bare `test` on its module; full reactor to read the
`realm-boundary` worklist shrink per zone.

Branch kept, never merged ([[external-worktree-operating-model-state]]). Folds the
[[doctor-graph-vs-dag-vocabulary-backlog]] rename. See [[world-exchange-document-design]]
[[realm-boundary-gate]] [[maven-build-cache-and-staging-verify]]
[[felixframeworkextension-renamed-outofcontainer]] [[options-always-as-c4-diagrams]].
