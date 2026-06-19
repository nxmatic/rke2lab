/**
 * Real-graph resolution proof: feeds rke2lab's cross-layer closure (reactor modules + manifest
 * domains + units) to the standalone Apache Felix resolver wrapped by {@code UnitResolver}, proving
 * the resolver computes the closure as a pure function of (units, constraints) — no framework, no
 * classloading.
 *
 * @deprecated The whole fixture builds the resolver's universe BY HAND ({@link
 *     io.nxmatic.rke2lab.unitrepo.realgraph.ReactorModuleCatalog} transcribes reactor module ids,
 *     {@link io.nxmatic.rke2lab.unitrepo.realgraph.ManifestsUniverse} re-expresses the domain/unit
 *     layer), which makes it a duplicated source of truth that already drifted from the real poms
 *     at the {@code -core}/{@code -port} split (ids are deliberately left stale, not re-synced).
 *     The proof is superseded once Felix boots for real and resolves actually-installed bundles (R4
 *     boot seam): at that point this entire {@code realgraph} package is DELETED, not repaired.
 *     Kept only until R4 lands so the standalone-resolver track stays demonstrated in the meantime.
 */
@Deprecated(forRemoval = true)
package io.nxmatic.rke2lab.unitrepo.realgraph;
