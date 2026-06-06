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
- [Config restructuring state](config-restructuring-state.md) — branch refactor/config; spec + migration plan COMMITTED in wip/ (55f69ecb, 69a49461); single Rke2labConfig DTO, two catalogs + registrar contribution, doctor=Increment 2; NEXT: execute the plan
- [Sequential no-compat workflow](sequential-no-compat-workflow.md) — single dev, one topic at a time; NEVER write backwards-compat; delete old paths in the same change; system fully on new path once merged
- [Domain registry abstraction](domain-registry-abstraction.md) — DEFERRED future branch refactor/domain-registry-abstraction; unify Manifest+Infra registry pairs once rule-of-three met (3rd instance or doctor domain model lands)
- [Claude distributed assets topic](claude-distributed-assets-topic.md) — POSTPONED: sharing Claude memory/skills across repos+Darwin hosts; nix-darwin-home module work reverted; rke2lab memory tracking (967388bc) kept & working; own branch later
