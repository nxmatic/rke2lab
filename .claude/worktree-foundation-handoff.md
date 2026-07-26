# Handoff — the `worktree` foundation component (the deferred git-port, surfaced)

**For:** the master conversation (sequential shared worktree).
**From:** the `feature/cluster-seed-scenario` working session, mid cellar-secrets Step 1.

## What surfaced

Wiring `worktree.dir` "derived at runtime" (cellar-secrets unified-input, item b) put a finger on
the git-edge the earlier handoff had **deferred as a separate refactor**. The symptom was real and
concrete: `new FileRepositoryBuilder().findGitDir(...)` was duplicated across the host
(`EntryGatePolicyEnforcer`) and OSGi (`incus-core/GitProvenanceReader`), and I was about to add a
third (worktree-root resolution). "No duplication — pose the worktree once, all usages rest on it."

## What I did (one increment)

A new **`osgi/foundation/worktree`** module — `type=library` dual-realm (the `incus-contract-host`
pattern: staged as a bundle OSGi-side, kept flat host-side, `DUPLICATE_REALM_CLASS` exempt). It
exports one type, `Worktree`:

- `Worktree.locatedFrom(startDir)` — jgit walks up to the enclosing `.git`, reports its working tree
  (the host derives `worktree.dir` from the process dir, robust to a subdirectory launch).
- `Worktree.at(root)` — adopt a known root (the reconstructed worktree / the entry-gate target).
- `Worktree.openRepository()` — the single `findGitDir` door, `Optional<Repository>` (empty when no
  `.git`); each caller applies its own policy.

**jgit legitimacy (dual-realm):** jgit carries a BSN, so it is a shared bundle OSGi-side and on the
flat classpath host-side — present in BOTH realms, in the `REALM_BOUNDARY` intersection (the guard is
a reachability check, not a JDK-only allowlist). **No jgit type crosses the broker seam**: `Worktree`
and the `Repository` are consumed locally in each realm, never placed in a `SeedEnvelope` (String-only
seam) — so no realm leak is possible.

**Three usages now rest on it (the two duplications killed + the new one):**
- `Main` — derives `worktree.dir` via `Worktree.locatedFrom(cwd).root()`.
- `EntryGatePolicyEnforcer` (host) — `Worktree.at(path).openRepository()`.
- `GitProvenanceReader` (**incus-core, your territory**) — `Worktree.at(root).openRepository()`,
  keeping its HEAD-resolve + `HostStagingEntry.Provenance` mapping.

**`worktree.dir` removed from the config DTO** — it is a runtime fact, not operator config: deleted
`Rke2labConfig.WorktreeConfig`, `InfraDomain.WORKTREE`, `InfraDomainCatalog.WORKTREE`,
`Rke2labConfig.worktree()`; `BootstrapConfig.from(config, worktreeRoot)` now takes the derived root.
Config tests updated (mandatory inputs are now `incus.configDir` + `image.sharedFolder` only).

## Gate / build status

- Full reactor `package` + **all tests green** (incl. seed-master config tests).
- `incus-core` full `verify` **green** — the OSGi-side worktree staging gates (`REALM_BOUNDARY`,
  `DUPLICATE_REALM_CLASS`) pass.
- `SPEC_COVERAGE` for the `Worktree` export: named in `docs/architecture/atlas/cellar-secrets.adoc`
  (the source-model section, where `worktree.dir`-derived-at-runtime already lived).
- **NOT confirmed here:** the full-reactor `verify` (seed-master's flat-side staging gate). Reason:
  `manifests-core`'s `shellcheck-maven-plugin` uses an `embedded` (x86) binary that fails on this
  arm64 session with no Rosetta ("Bad CPU type", error 86) — a pre-existing environment issue,
  unrelated to worktree. Your normal (Rosetta) env exercises it. The flat realm has jgit directly, so
  the flat-side `REALM_BOUNDARY` is symmetric to the OSGi one that passed.

## Open, unchanged

The neutral-git-port idea in the earlier handoff is now partly realised as this `worktree`
foundation component. If you want a broader git port (beyond worktree root + repo-open), it can grow
`Worktree` or sit beside it — not needed for cellar-secrets.
