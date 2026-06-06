---
name: config-restructuring-state
description: "Config refactor on branch refactor/config — diagram-first spec + migration plan committed in wip/, NOT yet implemented (no Java written)"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0ea4e055-08e0-405d-a509-7a32cda44c3e
---

Restructuring rke2lab configuration. Branch `refactor/config`. Spec + plan COMMITTED in `wip/`,
implementation NOT started (no Java written yet).
- `wip/config-restructuring-spec.adoc` — diagram-first design (C4 + UML, no code), committed 55f69ecb
- `wip/config-migration-plan.adoc` — TDD implementation plan, committed 69a49461

**Preview-only branch:** nothing deploys from refactor/config; `pulumi preview` is the only test.
Combined with [[sequential-no-compat-workflow]]: no green-between-commits machinery, old code
deleted in the same change that supersedes it. Branch only needs coherence when the topic is done.

**Goal:** kill the fragmented config (3 independent Pulumi `Config` readers —
`BootstrapConfig.Builder`, `ControlplanePolicy`, `ConfigResolver` — with divergent
`EnvironmentValues` wrappers and mixed env/git/user.home fallbacks). Replace with a single
source of truth + nested YAML. Motivated by Nix `nix run/exec` where git worktree + `$HOME`
don't exist.

**Locked design decisions (all user-approved):**
- One reader: `Rke2labConfig.from(Config)`. Everything else consumes the immutable DTO.
- All inputs in Pulumi config — no `System.getenv`, no `user.home`. `incus.configDir`
  mandatory (it's the Incus client-cert dir for the Pulumi Incus provider). `worktree.dir`
  mandatory (git detection removed; GitHub-clone fallback documented as future).
- Nested YAML (`rke2lab:incus: {project, configDir}`) not flat dotted keys.
- DTO field contract: **mandatory fields = plain types**, throw dedicated
  `MissingRequiredConfiguration` at load time; **optional fields = `Optional<T>`**, defaults
  applied LATER in the derivation layer (`BootstrapConfig.from`/`ControlplanePolicy.from`),
  never in the DTO.
- Fluent `ConfigLoader` reads scalars key-by-key (sidesteps Jackson's inability to bind
  `Optional`; only nested maps `policy.readiness.override`/`simulate` use `getObject`).
- Fluent derivation reuses the `ApplicationPipeline` grammar (`.during(label).then()`).
- **Two aligned catalogs:** keep `ManifestDomainCatalog` (Stage B); add `InfraDomainCatalog`
  (Stage A: incus, image, network, worktree, systemd, host). NOT merged — different layers.
- **Full registrar-style contribution** (user chose the costly path for extensibility):
  each infra domain = an `InfraDomainRegistrar` owning {config fragment + doctor specialist},
  assembled via `InfraConfigRegistryBuilder` → `InfraConfigRegistry`, mirroring
  `ManifestsDomainRegistrar`/`Registry`. Heterogeneous fragments → `sealed InfraConfigFragment`
  + one centralized cast in typed accessors (`config.incus()`).
- `policy.link.*` flags ARE manifest-domain IDs → `isLinkEnabled` MUST key off
  `ManifestDomainCatalog` (the clusterApi-vs-cluster-api discipline), not a hardcoded switch.
- **Doctor integration:** missing mandatory key = a domain-tagged *symptom*. NO config doctor,
  NO `ConfigSpecialist` — the config layer only knows structural facts; the existing per-domain
  specialist (e.g. IncusExecSpecialist) owns the meaning (why/example). Single shared
  `Generalist` routes by domain. Loader ACCUMULATES all missing keys, consults ONCE. Reuses the
  runbook-doctor subsystem (see that branch's `wip/spec.adoc`).

**Plan structure (MERGED, preview-only):** the spec's A/B/C collapse into ONE increment
(`config-migration-plan.adoc`, 9 tasks): ConfigLoader+DTO+catalog+sealed-fragment+registry+6
registrars → derive BootstrapConfig (delete Builder/Defaults/env-git) → derive ControlplanePolicy
(delete EnvironmentValues, catalog-keyed links) → delete ConfigResolver + converge EnvironmentStage
(reads DTO once) → nested Pulumi.dev.yaml + BDD helper. The doctor (spec's state D) is **Increment 2**,
a separate plan = the doctor pattern's FIRST use case.

Plan refinements vs spec: ConfigLoader uses **section-map reads** (`getObject(section)` → walk
dotted names like `policy.link`) over an injectable `SectionReader`, so the DTO is unit-testable
offline (in-memory maps) — NOT scalar key-by-key as the spec loosely said. DTO has a `defaults()`
offline path (no mandatory validation, for EnvironmentStage's null-context branch). `BootstrapOptions`
(readiness/cleanWorktree/bbox, ex-ConfigResolver) absorbed as cross-cutting DTO records.

**Deferred:** [[domain-registry-abstraction]] — unify the two registry pairs later.

**Next step when resuming:** EXECUTE `wip/config-migration-plan.adoc` (subagent-driven or inline).
Builds run by user via `flox activate -- ./mvnw`; agent proposes commands, never runs mvnw/pulumi.
