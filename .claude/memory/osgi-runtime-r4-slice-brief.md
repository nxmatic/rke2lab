---
name: osgi-runtime-r4-slice-brief
description: "EXECUTION BRIEF for the R4 slice (worktree feature/osgi-runtime-r4-boot-seam, off origin/design/target-module-layout @68d2c737, set up 2026-06-19): boot Felix inside seed-master's Pulumi.run callback + wire the host consume-seam onto the registry. The DESIGN is fully in [[osgi-runtime-r4-boot-seam-state]] (the knot, the embed-bundles-intact decision, the pulumi-preview proof) — read that FIRST, it is the spec; this note is only the milestone plan + close discipline. Structured in THREE hard milestones with a go/no-go after A: A = boot seam proven by `pulumi preview`; B = the 3 wrong-direction inversions; C = delete the tombstoned realgraph fixture. One worktree, separate logical commits, ONE squash at merge. NOT coded. Build + pulumi preview only, NO -Plive up."
metadata:
  node_type: memory
  type: project
---

## Read first — the spec is the carto

[[osgi-runtime-r4-boot-seam-state]] carries the full design and is authoritative:
- §THE KNOT — the exec-jar shades osgi/ bundles FLAT (OSGI-INF/Service-Component drowned), Pulumi.yaml
  launches ONE `binary:` jar → Felix can't `installBundle()` discrete bundles. This is what R4 solves.
- §DECISION — self-contained artifact: EMBED the osgi/ bundles INTACT inside the seed-master exec-jar
  (stop shading them flat), Felix extracts+installs at boot; environment supplies only config.
- §proof path — `pulumi preview` (a dry-run, in-scope per CLAUDE.md, boots Felix for real WITHOUT
  mutating master). NOT `-Plive up` (that stays the user's optional final gesture).
- §What is already known + easy — the non-knot parts (the seam wiring points).

This brief does NOT restate the design; if the two ever disagree, the carto wins.

## The three milestones (one worktree, go/no-go after A)

**Milestone A — boot seam, proven by `pulumi preview` (the architectural lift; GO/NO-GO).**
Stop shading the osgi/ bundles flat in the seed-master exec-jar; embed them intact; boot Felix +
felix.scr inside the `Pulumi.run` callback; SCR publishes the `@Component` services; the host seam reads
them from the registry. PROOF = `pulumi preview` boots Felix for real, SCR publishes, the seam consumes,
preview completes with no master mutation. Also add an embedded-Felix host-scope test (reuse osgi/testkit)
proving the seam resolves the services from a booted framework. ★ This is the real risk — if the embed +
boot doesn't prove out, STOP and report before B/C (the bail-out point). gRPC stays flat (issue #1565).

**Milestone B — the 3 wrong-direction inversions.** The host currently reaches INTO impl types:
`ManifestYaml`, `NodeEnvContributorRegistry` (host `new`s an impl), `FloxRuntimeAssets`. Invert each so
the host depends on a port/registry-published service, not the impl (per [[api-extraction-tri-carto-state]]
— these were deliberately deferred from the port re-placement to ride with R4). Each inversion lands its
own commit.

**Milestone C — delete the tombstoned realgraph fixture.** Once Felix boots and resolves
actually-installed bundles (Milestone A), the hand-fed standalone-resolver proof is redundant. DELETE the
whole `exec/seed-master` test package `io.nxmatic.rke2lab.unitrepo.realgraph` (7 files, already
`@Deprecated(forRemoval = true)`). KEEP `UnitResolver` itself (wraps Felix ResolverImpl, stays in
production: `ManifestsVisitOrder`, `ManifestsDomainRegistry`). Per [[m2-snapshot-masking-is-critical]]
spirit — when a thing is superseded, delete it, don't leave it. Recorded in [[java-cleanup-backlog]].

## Validation (the gate before handoff)

- FULL `flox activate -- ./mvnw clean package -Posgi -Dmaven.build.cache.skipCache=true -DskipTests=false`
  from the worktree; COUNT surefire reports ([[build-verification-gotchas]]) — a green build with no
  `Tests run:` is skipped, not passed. After C the realgraph 5 tests are GONE, so expect the count to
  DROP by 5 (12 → 7 reports) — that drop is the proof C landed, not a regression; say so explicitly.
- `pulumi preview` from the repo root (flox) is Milestone A's behavioural proof — boots Felix in the
  callback, no master mutation. The `mvn install` guard ([[osgi-baseline-install-discipline]]) is active:
  never `mvn install` to resolve a sibling — `-am` from sources only.
- NO `-Plive up` (the user's optional gesture).

## Workspace / close discipline

- Worktree `feature/osgi-runtime-r4-boot-seam`, base `origin/design/target-module-layout` @68d2c737
  (pushed before setup). External-worktree model; `.code-workspace` sibling carries CLAUDE_CONFIG_DIR →
  the worktree's own `.claude`; null-analysis `disabled` (matches the backlog interim). sops re-smudged
  at setup (`.ndh-ssh.d/keys.yaml`; the schema's `ENC[` are comment text).
- Build + `pulumi preview` only → inside [[standing-autonomy-except-runtime-config]]. Act without asking
  on code/preview; STOP and report at the Milestone-A go/no-go and before any `-Plive`.
- CLOSE = commit code AND `.claude/memory/` (separate logical commits per milestone), build GREEN +
  preview clean, then HAND OFF to the design/target-module-layout session for the ONE squash-merge — this
  session does NOT saw its own worktree ([[merge-from-target-worktree]]). After merge: flip
  [[osgi-runtime-r4-boot-seam-state]] + the R5 line in [[osgi-runtime-migration-state]], update the atlas
  runtime view (R4 SHIPPED, realgraph deleted).

See [[osgi-runtime-r4-boot-seam-state]] (THE SPEC), [[api-extraction-tri-carto-state]] (the 3 inversions),
[[osgi-cleanup-slice-state]] (what unblocked this — versioned ports), [[build-verification-gotchas]],
[[osgi-baseline-install-discipline]], [[merge-from-target-worktree]], [[standing-autonomy-except-runtime-config]].
