// @codebase
/**
 * Cluster domain manifest units: core cluster-scoped resources other domains build on.
 *
 * <ul>
 *   <li>{@link io.nxmatic.rke2lab.manifests.units.cluster.ClusterRuntimeNamespaceManifestsUnit} —
 *       the runtime namespace many other units depend on.
 *   <li>{@link io.nxmatic.rke2lab.manifests.units.cluster.ClusterNodeEnvContributor} — cluster
 *       slice of the node environment.
 *   <li>{@link io.nxmatic.rke2lab.manifests.units.cluster.ClusterRefs} — shared resource references
 *       for cross-unit wiring.
 * </ul>
 *
 * <p>Registered by {@link io.nxmatic.rke2lab.manifests.domain.ClusterDomainRegistrar}.
 *
 * <h2>Related documentation</h2>
 *
 * <ul>
 *   <li><a href="../../../../../../../../../../docs/manifests-architecture.adoc">Manifests
 *       Architecture</a> — the unit model and synthesis flow.
 * </ul>
 */
package io.nxmatic.rke2lab.manifests.units.cluster;
