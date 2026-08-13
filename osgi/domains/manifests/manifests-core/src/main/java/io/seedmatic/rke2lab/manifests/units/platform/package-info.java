// @codebase
/**
 * Platform domain manifest units: cross-cutting platform services.
 *
 * <ul>
 *   <li>{@link io.seedmatic.rke2lab.manifests.units.platform.CertManagerManifestsUnit} —
 *       cert-manager (a dedicated domain; note the Chart-vs-Construct incident in the architecture
 *       doc).
 *   <li>{@link io.seedmatic.rke2lab.manifests.units.platform.ReplicatorManifestsUnit} —
 *       kubernetes-replicator for secret/config replication across namespaces.
 * </ul>
 *
 * <p>Registered by {@link io.seedmatic.rke2lab.manifests.domain.PlatformDomainRegistrar}.
 *
 * <h2>Related documentation</h2>
 *
 * <ul>
 *   <li><a href="../../../../../../../../../../docs/manifests-architecture.adoc">Manifests
 *       Architecture</a> — the unit model, synthesis flow, and Chart-vs-Construct rule.
 * </ul>
 */
@org.jspecify.annotations.NullMarked
package io.seedmatic.rke2lab.manifests.units.platform;
