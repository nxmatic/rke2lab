/**
 * The worktree domain's dual-realm HOST face — the cellar-harvested wire types BOTH realms consume:
 * {@link WorktreeFacts} (the {@code @SeedContract} snapshot) at the {@link WorktreeCoordinate}
 * cellar coordinate, carrying the HEAD {@link Provenance} and the {@link WorkingState}. Same form
 * as {@code incus-contract-host}: {@code type=library}, staged as a bundle OSGi-side AND kept flat
 * host-side, so the flat host decodes {@link WorktreeFacts} into its OWN copy — no jgit type
 * crosses. The {@code Worktree} service interface (worktree-contract) returns {@link Provenance} /
 * {@link WorkingState} from here.
 *
 * <p>SPEC_COVERAGE is held at {@code WARN} (acknowledged debt, not IGNORE): the component's full
 * contract has no dedicated {@code docs/} spec yet — the atlas {@code cellar-secrets} note explains
 * only WHY the worktree is derived. The worktree component spec is the master's to write (see the
 * {@code feature/cluster-seed-scenario} handoff); once written, drop this annotation to relock the
 * package at the default {@code ERROR}.
 */
@org.osgi.annotation.versioning.Version("1.0.0")
@org.jspecify.annotations.NullMarked
@io.nxmatic.rke2lab.domain.annotations.GovernedBy(
    value = io.nxmatic.rke2lab.domain.annotations.StagingGate.SPEC_COVERAGE,
    level = io.nxmatic.rke2lab.domain.annotations.EnforcementLevel.WARN)
package io.nxmatic.rke2lab.worktree.host;
