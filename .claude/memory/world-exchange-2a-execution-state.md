---
name: world-exchange-2a-execution-state
description: World-exchange 2A (the Document foundation increment) is SHIPPED on feature/cluster-edge (2026-06-27). The readiness verdict crosses the host↔OSGi seam as a structured Document; ControlplanePolicy is doctor-free; a Plan-1 gate timing flaw (cold-tree deadlock) was fixed; and the in-container proxy tests were refactored to DERIVE their install closure from the host instead of hand-listing bundles. Next is 2B.
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

## NEXT

2B (per [[world-exchange-document-design]]) — fold the doctor-graph→DAG rename in
([[doctor-graph-vs-dag-vocabulary-backlog]]). The probe path still parses `Symptom` host-side
(preview-simulate); that migration is 2B's. Branch kept, never merged ([[external-worktree-operating-model-state]]).
See [[realm-boundary-gate]] [[maven-build-cache-and-staging-verify]] [[felixframeworkextension-renamed-outofcontainer]].
