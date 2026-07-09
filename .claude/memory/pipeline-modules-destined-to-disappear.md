---
name: pipeline-modules-destined-to-disappear
description: "The pipeline/ modules (pipeline-jgiven, pipeline-testkit, pipeline-port) are TRANSITIONAL and destined to disappear once the BDD-as-engine migration completes; the jGiven-by-two-paths mess and the unwired consult are symptoms of that unfinished migration, not standalone bugs."
metadata:
  type: project
---

**User frame (2026-07-08):** the disorder around jGiven dependencies is NOT an isolated
declaration bug — it "sits around the pipeline migration, which is not finished. To term, the
`pipeline` modules should disappear."

**Why:** jGiven is reachable by TWO paths today, and that duplication is the root of mis-declared
deps —
- `osgi/foundation/pipeline/pipeline-jgiven` = the pure wrap (jgiven-core + jgiven-junit5 stamped
  OSGi), **test-scope only** ("no production bundle imports jGiven").
- `osgi/runtime/scenario-engine` = the launcher/engine home (`JUnitLauncherCore`,
  `OutOfContainerFrameworkExtension`, `OsgiConnection`) that ALSO re-carries jGiven at
  **runtime-scope** (the "dogfooding promotion" — the seeding IS jGiven scenarios played by an
  embedded JUnit launcher, so that machinery is no longer test-only).

So a module wanting jGiven can pull it via `scenario-engine` (dragging the launcher) OR via
`pipeline-jgiven`. The BDD-as-engine target ([[cluster-seed-execution-state]], `docs/.../bdd/bdd.adoc`)
is that `scenario-engine` is the ONE runtime home; the `pipeline-*` path is the AVANT, destined to
erase — exactly the fluent-grammar dissolution CLAUDE.md describes (`pipeline-port` still exports the
fluent types only because live code still uses them; when the last pipeline migrates, those exports
shrink and the modules go).

**Measured state (2026-07-08), scenario-engine dependents:**
- LEGITIMATE (use the launcher/harness, no jGiven import): `cluster-edge`, `dbus-systemd-edge` (both
  use `OutOfContainerFrameworkExtension`); `seed-master` (uses `OsgiConnection` + `JUnitLauncherCore`
  + jGiven API — all real).
- SUSPECT (neither launcher nor jGiven types in src): `incus-edge`, `bbox-edge` — a `scenario-engine`
  dep they do not use in source (transitive/test residue or dead). Flag when the migration sweeps.
- `pipeline-jgiven` dependents: `cluster-bdd`, `doctor-core-test`, `manifests-core-test`,
  `pipeline-testkit`.

**The unwired `consult` is the SAME symptom.** In production there is NO
`@Component(service=ConsultingService.class)` — only `FakeConsultingService` publishes it. The real
`consult` lives on `Generalist`, assembled PER-PATIENT via `HealthSystem.admit(Patient)`, which is
called ONLY in doctor-core-test — never host-side. The host's
`awaitService(ConsultingService.class)` therefore resolves only against the fake today: the real
consult path is **not yet wired in prod**. This is a migration gap, not a broker-design problem —
the broker (Phase 1) must not try to invent the real-consult publication; that belongs to the
doctor's own migration increment. See [[gateway-is-rest-in-jvm-insight]] Phase 1.

**How to apply:** do NOT treat the jGiven dep cleanup as a standalone chore now. It is part of the
pipeline migration; fix it when that migration sweeps (and expect the `pipeline-*` modules to be
deleted, not repaired). When designing the seed-broker door, remember `consult` is asymmetric from
`assess`/`canonicalize` (per-patient admission, currently fake-only) — a known gap to carry, not to
paper over.
