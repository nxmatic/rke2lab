# Memory index

- [Manifests doc consolidation](manifests-doc-consolidation.md) — DONE 2026-06-03: hub manifests-architecture.adoc + 4 companions; 4 docs deleted; domain-registry path documented as dormant
- [Terminology refactor state](terminology-refactor-state.md) — manifests module renames (ManifestsUnit/ManifestsDomain/NodeEnv) done; BootstrapPhase + DomainRegistrars pending
- [Refactor pipeline candidates](refactor-pipeline-candidates.md) — 9 méthodes à refactorer (fluent grammar/builder/record); checklist dans repo .claude/; start with synthesizeInContext
- [Package-private sweep](package-private-sweep.md) — retirer private non essentiels (3131 sur 316 fichiers); module par module; exemplar = DefaultManifestSynthesisService
- [seed-vcluster](seed-vcluster.md) — prochain chantier: amorcer vCluster gitops-mgmt + Flux (ex layer 2); prérequis manquants: vcluster operator unit + pulumi-command
- [Shared artifacts in English](shared-artifacts-in-english.md) — docs/comments/commits must be en-US, never French; rke2-install-phases.adoc had a French slip to fix
- [Local classes pattern](local-classes-pattern.md) — Use local classes in methods (not inner) when single-use & simple; applied to ImageStateSynthesizer + NetworkEnsurer
- [Bedrock compaction issue](bedrock-compaction-issue.md) — Auto-compaction fails; proactively warn at 80k/120k tokens before "input too long" error
- [BDD/JGiven test strategy](bdd-jgiven-test-strategy.md) — tests = living docs (better than a manual); JGiven BDD on real module use-cases; DSL-first prototype before wiring; entry-point map of all modules
- [Master provisioning state](master-provisioning-state.md) — config.yaml.d FIXED; now blocked on systemd-adapter dbus-over-TCP probe (port 12434 refused); want to RESTRICT master apps to vcluster-bootstrap minimum
- [Working style: narrate progress](working-style-narrate-progress.md) — narrate intent before each tool batch; long silent gaps read as stuck; cap investigation
- [Runbook+doctor state](runbook-doctor-state.md) — feature/runbook-doctor: Increments A-D + BDD-quality pass DONE; DAG chantier has a refined medical model (patient=Pulumi stack; Dossier=consultation report; symptom-set; Doctor=generic module; record=reconstructable state). Design in wip/spec.adoc + wip/plan.adoc + wip/pulumi-doctor-integration.adoc (930069c1). NEXT=layer-2 persist; Pulumi fork RESOLVED (doctor=app logic not a resource, Option A); T2/T3/T4 open
- [Config restructuring state](config-restructuring-state.md) — Increment 1 (Rke2labConfig DTO + InfraDomain enum + entry-gate BDD) DONE & VERIFIED & MERGED to main (18 tests + pulumi preview); Increment 2 (doctor remediation) = the doctor's first use case, waits on [[runbook-doctor-state]]
- [wip-guard hooks](wip-guard-hooks.md) — .githooks/ pre-commit + pre-push block wip/ reaching main; wired via core.hooksPath in flox on-activate; feature branches keep wip/
- [Sequential no-compat workflow](sequential-no-compat-workflow.md) — single dev, one topic at a time; NEVER write backwards-compat; delete old paths in the same change; system fully on new path once merged
- [Domain registry abstraction](domain-registry-abstraction.md) — DEFERRED future branch refactor/domain-registry-abstraction; unify Manifest+Infra registry pairs once rule-of-three met (3rd instance or doctor domain model lands)
- [Manifest registrars enum refactor](manifest-registrars-enum-refactor.md) — NEXT-TOPIC after config: refactor 11 manifest domain registrars to an enum (same pattern as config InfraDomain); closed set, applies; defer — different module, heavier, touches dormant buildDomainRegistry
- [Claude distributed assets topic](claude-distributed-assets-topic.md) — POSTPONED: sharing Claude memory/skills across repos+Darwin hosts; nix-darwin-home module work reverted; rke2lab memory tracking (967388bc) kept & working; own branch later
