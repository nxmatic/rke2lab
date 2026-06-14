# Memory index — rke2lab

Project-specific memory for rke2lab. Cross-cutting facts (profile, conventions, principles) + cross-repo chantiers (maven fleet, docrepo, wip-guard) live in the **hub** ([[hub:MEMORY]] at `/private/var/lib/git/nxmatic/claude-memory`), auto-loaded as the session root. One line per entry; detail in the file. Links: `[[name]]` = rke2lab-local, `[[hub:name]]` = hub.

## Active chantiers

- [Intervention-provenance state](intervention-provenance-state.md) — **★ SHIPPED to main 2026-06-14 (origin/main 7e5ec7d1; 260 tests green).** Problem=(Checkpoint+opt Symptom) join key; DriftSpecialist infers/explains resolutions (idempotent) → efficacy marks confounded. ONLY OPEN = e2e LIVE proof, gated on [[pulumi-stack-per-worktree-backlog]]. See [[hub:specialist-as-ledger-northstar]] [[hub:model-substrate-alignment]] [[validate-at-the-boundary]] [[builder-for-multisite-constructor]].
- [Master provisioning state](master-provisioning-state.md) — **★ live-probe contract FIXED+PROVEN vs real master** (branch fix/systemd-live-probe-contract, not merged): gate returns Observation.failed→doctor consulted→runbook persisted. PARKED. NEXT-topic seed = operator's out-of-band intervention not in stack history → the drift specialist data source.

## Doctor / health system (the big subsystem)

- [Runbook+doctor state](runbook-doctor-state.md) — **SHIPPED.** Layer-3 write-side (per-node registerOutputs, additive consultationReport) + read-side runtime enrichment (⚕/℞ render into runbook .adoc, join by Checkpoint.scenarioTitle). Pulumi fork resolved (doctor=app logic).
- [HealthSystem keystone state](healthsystem-keystone-state.md) — **SHIPPED.** Per-run HealthSystem holds registry + GrantPolicy, employs Generalist via credentialed ClinicalAccess. DEFERRED seam = referral-derived grants (GrantPolicy is the swap-in point).
- [Referral round-trip state](referral-roundtrip-state.md) — **SHIPPED.** diagnose(Referral)→ReferralReply: specialist always returns an Assessment. 4 types + 2 fake specialists; 🔬Assessment vs ℞Mitigation in runbook. 150 tests.
- [Medical-record impl complete](medical-record-impl-complete.md) — **SHIPPED.** On-demand query API reconstructed from Pulumi; JGiven TCP forkNode, deployment-instant ordering, per-symptom efficacy.
- [Medical-record query-API state](medical-record-query-api-state.md) — design+plan of the on-demand query API (accumulator abandoned). Superseded by impl-complete.
- [Doctor live-record roadmap](doctor-live-record-roadmap.md) — 3-step chain; step1 + access-control SHIPPED. Remaining: step2 remediation/Referral round-trip.
- [Doctor remediation model](doctor-remediation-model.md) — design: Remediator tier=the "hands" (doctor stays pure); loop closure=next visit; recruit-a-specialist gradient ad-hoc→codified.
- [HealthSystem access-control model](healthsystem-access-control-model.md) — original north-star brainstorm; SUPERSEDED by keystone-state (kept for design history).
- [Cohort-correlation spike](cohort-correlation-spike.md) — proved cross-patient correlation; PROMOTED+SHIPPED in the keystone; spike branch deleted.
- [Preview what-if topic](preview-whatif-topic.md) — PARKED: preview replays BDD on observed⊕hypothesis. Patient record = TOP-LEVEL ctx.export read via self-StackReference. Resume cold from file.
- [Efficacy first-prescription provisional](efficacy-first-prescription-provisional.md) — MedicalRecord.efficacyOf: per-symptom gate SETTLED; "first prescription" pick PROVISIONAL (revisit before efficacy drives a decision).
- [ServiceLoader Specialist SPI](serviceloader-specialist-spi.md) — PARKED: doctor roster=extension point; real target=integrate with user's federated OSGi system.
- [Seeded history via Automation API](seeded-history-automation-api.md) — seed a TEST stack via Automation API (exportStack→mutate→importStack); visits tagged 'seeded' via Dossier.details.
- [Task 14 readonly-preview integration](task14-readonly-preview-integration.md) — export dev state→throwaway stack→pulumi preview only; revealed+fixed the version=0 bug.

## Infra / manifests / config

- [Config restructuring state](config-restructuring-state.md) — Inc1 (Rke2labConfig DTO + InfraDomain enum) MERGED; Inc2 (doctor remediation) waits on doctor work.
- [Manifests doc consolidation](manifests-doc-consolidation.md) — DONE: hub manifests-architecture.adoc + 4 companions; domain-registry path documented as dormant.
- [Terminology refactor state](terminology-refactor-state.md) — manifests renames done (ManifestsUnit/Domain/NodeEnv); BootstrapPhase + DomainRegistrars pending.
- [Manifest registrars enum refactor](manifest-registrars-enum-refactor.md) — NEXT-TOPIC: refactor 11 registrars to an enum; defer (touches dormant buildDomainRegistry).
- [Refactor pipeline candidates](refactor-pipeline-candidates.md) — 9 methods to refactor (fluent grammar/builder/record); start with synthesizeInContext.
- [Package-private sweep](package-private-sweep.md) — remove non-essential private (3131 across 316 files); exemplar = DefaultManifestSynthesisService.
- [Domain registry abstraction](domain-registry-abstraction.md) — DEFERRED; unify Manifest+Infra registry pairs at rule-of-three.
- [seed-vcluster](seed-vcluster.md) — next chantier: bootstrap vCluster gitops-mgmt + Flux; missing prereqs: vcluster operator unit + pulumi-command.

## rke2lab gotchas / patterns / test strategy

- [Build verification gotchas](build-verification-gotchas.md) — green build LIES: .mvn forces -DskipTests + build-cache replays + IDE ECJ poison .class. Reliable: `flox activate -- ./mvnw clean package -pl :seed-master -am -Dmaven.build.cache.skipCache=true -DskipTests=false`; count surefire reports.
- [BDD/JGiven test strategy](bdd-jgiven-test-strategy.md) — tests = living docs; JGiven BDD on real module use-cases; DSL-first prototype before wiring.
- [DSL unification topic](dsl-unification-topic.md) — PARKED branch refactor/jgiven-shared-engine: ONE engine (JGiven), TWO layers. NEXT=prototype PreflightScenario.
- [Builder for multisite constructor](builder-for-multisite-constructor.md) — builder pattern note (rke2lab construction).
- [Validate at the boundary](validate-at-the-boundary.md) — validation discipline (referenced by intervention-provenance).
- [Pulumi stack per worktree](pulumi-stack-per-worktree-backlog.md) — BACKLOG: flox PULUMI_BACKEND_URL project-relative → each worktree EMPTY state; real dev only in main. Bundles [[sops-worktree-smudge-noise]]. (cross-cutting gotcha; promote to hub later.)
- [Sops worktree re-smudge](sops-worktree-smudge-noise.md) — `git worktree add` leaves sops files ENCRYPTED; FIX: `rm <sops files> && git checkout -- <them>`. (cross-cutting gotcha; promote to hub later.)
