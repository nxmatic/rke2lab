---
name: spec-coverage-gate-state
description: "DONE (2026-06-26, UNCOMMITTED) — the staging-gates governance model: THREE build-time laws in the staging-extension (RECORD_PURITY, SPEC_COVERAGE, INSTANCE_DISCIPLINE) read from bundle bytecode via ASM, each governable per-bundle by @GovernedBy(Gate, EnforcementLevel) on a package-info (default ERROR=locked, WARN=visible backlog, IGNORE=infra). Single-element exemptions via @Exempt(Gate, reason). fail-AT-end with a per-gate summary. Spec'd in docs/architecture/osgi/staging-gates-governance-spec.adoc. Build GREEN, all debt at WARN. Remaining: the commit."
metadata:
  node_type: memory
  type: project
---

## What this is

A build-time governance model in `maven-embed-staging-ext/staging-extension` (twin lineage of
`RecordPurity`). THREE staging laws, each an instance reached from `ResolvedBundle`
([[object-graph-navigability-principle]]), read from bytecode via ASM (the extension is installed
BEFORE the reactor that builds `domain-annotations`, so it CANNOT link it — it mirrors the enums, like
`EmbedCapability` mirrors bnd strings):

- **RECORD_PURITY** (`RecordPurity`) — a `type=record` bundle exports only records/enums/sealed ADTs.
- **SPEC_COVERAGE** (`SpecCoverage`) — every exported type is named in a `docs/` spec or `@Transitional`.
- **INSTANCE_DISCIPLINE** (`InstanceDiscipline`) — no exported `public static` behaviour helper; the
  TARGET IS ZERO. Factories (`of/from*/parse/valueOf/builder/create/defaults/new*`, or returns-self)
  are part of the rule, not exceptions. Anything else that must stay static is annotated `@Exempt`.

## Why it exists (the load-bearing decision)

[[build-gates-over-review-reminders]]: a discipline the user keeps re-asking for at review becomes a
BUILD-TIME GATE, seen at every build, and once the debt is cleared the default-ERROR level LOCKS it so
the anti-pattern can never silently reappear. "la ca sera visible, tu le verras a chaque build,
impossible d'oublier. et une fois la dette epuree, impossible de recommencer."

## The governance model (all settled with the user, 2026-06-26)

- `EnforcementLevel { IGNORE, WARN, ERROR }`, default **ERROR**. NOT "DriftLevel" — the word *drift* is
  saturated in the doctor domain. ERROR breaks the build; WARN lists the real violation types (green,
  visible backlog); IGNORE is silent (build infra only).
- `@GovernedBy(Gate, EnforcementLevel)` — `@Repeatable`, `@Target(PACKAGE)`, on a `package-info`. One
  pose per gate. A gate with no pose for a package = ERROR (governed by default). The container the
  compiler folds repeats into is `@GovernedByAll`; the reader (`GovernanceReader`) handles both shapes
  → `Map<Gate, EnforcementLevel>`.
- `@Exempt(Gate, reason)` — `@Repeatable`, `@Target({TYPE,METHOD,FIELD})`, container `@ExemptAll`.
  Removes ONE element from ONE gate with a mandatory reason. The opposite of widening the heuristic:
  the rule stays strict, the residue is explicit/reviewable/countable. Only `InstanceDiscipline` reads
  it so far.
- All annotations live in `osgi/domain-annotations` (dependency-free, common to all domains; dep
  declared PER-BUNDLE, not in bundle-parent — user: deterministic). Retention CLASS (bytecode-visible).
- `enforceGates` runs fail-AT-end: collect ALL violations across ALL bundles, ERROR→single aggregated
  failure, WARN→logged backlog, plus a per-gate SUMMARY line (`record-purity: N error, M warn`…).

## State of the working tree (UNCOMMITTED, build GREEN)

`./mvnw -pl :seed-master -am clean package -Posgi -Dmaven.build.cache.skipCache=true` → BUILD SUCCESS.
Per-gate summary: record-purity 0/0, spec-coverage 0e/39w, instance-discipline 0e/14w.

- domain-annotations: `Gate`, `EnforcementLevel`, `@GovernedBy`+`@GovernedByAll`, `@Exempt`+`@ExemptAll`,
  `@Transitional` (kept). `@SpecGoverned`/`DriftLevel` DELETED (abandoned intermediate models).
- extension: mirror enums `Gate`/`EnforcementLevel`, `GovernanceReader`, `InstanceDiscipline`,
  `SpecCoverage` (governance logic removed), `enforceGates`+`GateReport` (level branching + summary).
  Tests: RecordPurity 5, SpecCoverage 4, GovernanceReader 3, InstanceDiscipline 6 — all GREEN.
- package-infos: 8 domains at `@GovernedBy(SPEC_COVERAGE, WARN)`; domain-annotations at IGNORE; the 5
  bundles with static helpers (pipeline, manifests-core, netplan-port, doctor-port, doctor-core) at
  `@GovernedBy(INSTANCE_DISCIPLINE, WARN)`. doctor-port/pipeline poms got the domain-annotations dep.
- `ConsultationNarration` documented in practitioners-as-components-design.adoc (was real SPEC drift).
- Spec: `docs/architecture/osgi/staging-gates-governance-spec.adoc` (+ README entry). The whiteboard
  `.claude/claude-preview.adoc` holds the frozen C4.

## The backlog the gates now expose (état des lieux, all WARN)

- INSTANCE_DISCIPLINE (14): pipeline:FluentTopicRunner#runDuring; manifests-core:ManifestYaml#{dump,
  writeDocument,writeDocuments,readNodes,readValues×3,mapper}+ManifestSynthesisContext#bind;
  netplan-port:Cidr#parseAddress+ClusterNetworkBlueprint#topology; doctor-port:ConsultationNarration#
  consultedLine; doctor-core:ExactRosterDoctor#over.
- SPEC_COVERAGE (39): the unspecified exported types of unitrepo/manifests/netplan/systemd/cluster.

## Remaining

- Committed as ONE batch (the rule + its verrou), including `maven-embed-staging-ext/.mvn` — the
  user's symlink so `../mvnw` installs the extension from inside the module (user asked to commit it).
- Deferred (separate slices, user-confirmed): the lines+imports severity indicator for static helpers
  (a refinement, noted not built); paying down the WARN backlog domain by domain then raising to ERROR.

See [[build-gates-over-review-reminders]] [[specs-current-at-brainstorm-end]] [[options-always-as-c4-diagrams]]
[[object-graph-navigability-principle]] [[medecin-conseil-efficacy-analyst-design]] [[practitioners-as-components-design]].
