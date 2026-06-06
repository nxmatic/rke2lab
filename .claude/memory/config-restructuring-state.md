---
name: config-restructuring-state
description: "Config refactor on branch refactor/config — design spec in wip/, decisions locked, not yet implemented"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0ea4e055-08e0-405d-a509-7a32cda44c3e
---

Restructuring rke2lab configuration. Branch `refactor/config`. Design spec lives at
`wip/config-restructuring-spec.adoc` (brainstorm phase — NOT yet implemented; no code written).

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

**Increments in spec:** A) DTO + ConfigLoader + nested YAML + dead-key cleanup; B) derive
BootstrapConfig (delete Builder/Defaults/env-git detection); C) derive ControlplanePolicy +
delete ConfigResolver + catalog-keyed link flags; D) doctor-backed remediation (depends on
runbook-doctor Increment B).

**Deferred:** [[domain-registry-abstraction]] — unify the two registry pairs later.

**Next step when resuming:** spec self-review pass, then user review gate, then writing-plans.
Open thread the user was probing: the schema is effectively defined by the diagram/record
decomposition; field nullability resolved (mandatory=plain, optional=Optional).
