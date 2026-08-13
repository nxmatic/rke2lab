// @codebase
/**
 * Cluster domain manifest units: core cluster-scoped resources other domains build on.
 *
 * <ul>
 *   <li>{@link io.seedmatic.rke2lab.manifests.units.cluster.ClusterRuntimeNamespaceManifestsUnit} —
 *       the runtime namespace many other units depend on.
 *   <li>{@link io.seedmatic.rke2lab.manifests.units.cluster.ClusterRefs} — shared resource
 *       references for cross-unit wiring.
 * </ul>
 *
 * <p>Registered by {@link io.seedmatic.rke2lab.manifests.domain.ClusterDomainRegistrar}.
 *
 * <h2>Related documentation</h2>
 *
 * <ul>
 *   <li><a href="../../../../../../../../../../docs/manifests-architecture.adoc">Manifests
 *       Architecture</a> — the unit model and synthesis flow.
 * </ul>
 */
@org.jspecify.annotations.NullMarked
package io.seedmatic.rke2lab.manifests.units.cluster;
