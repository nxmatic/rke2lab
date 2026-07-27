/**
 * The worktree domain's dual-realm HOST face — the cellar-harvested wire types BOTH realms consume:
 * {@link WorktreeFacts} (the {@code @SeedContract} snapshot) at the {@link WorktreeCoordinate}
 * cellar coordinate, carrying the HEAD {@link Provenance} and the {@link WorkingState}. Same form
 * as {@code incus-contract-host}: {@code type=library}, staged as a bundle OSGi-side AND kept flat
 * host-side, so the flat host decodes {@link WorktreeFacts} into its OWN copy — no jgit type
 * crosses. The {@code Worktree} service interface (worktree-contract) returns {@link Provenance} /
 * {@link WorkingState} from here.
 *
 * <p>SPEC_COVERAGE is held at {@code WARN} (acknowledged debt, not IGNORE). The spec now exists —
 * {@code docs/architecture/osgi/worktree-component-spec.adoc} names every export — so the
 * documentation debt is paid. The {@code WARN}→default-{@code ERROR} relock is deferred to the Q1+Q2
 * session, which dissolves this dual-realm face into the OSGi-only {@code worktree-contract} (see the
 * spec § refinement); relocking the FINAL package once, rather than a package about to move.
 */
@org.osgi.annotation.versioning.Version("1.0.0")
@org.jspecify.annotations.NullMarked
@io.nxmatic.rke2lab.domain.annotations.GovernedBy(
    value = io.nxmatic.rke2lab.domain.annotations.StagingGate.SPEC_COVERAGE,
    level = io.nxmatic.rke2lab.domain.annotations.EnforcementLevel.WARN)
package io.nxmatic.rke2lab.worktree.host;
