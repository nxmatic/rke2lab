// @codebase
/**
 * High-availability domain manifest units: control-plane VIP and HA node environment.
 *
 * <ul>
 *   <li>{@link io.nxmatic.rke2lab.manifests.units.ha.KubeVipManifestsUnit} — kube-vip for the
 *       control-plane virtual IP.
 *   <li>{@link io.nxmatic.rke2lab.manifests.units.ha.HighAvailabilityNodeEnvContributor} — HA slice
 *       of the node environment.
 * </ul>
 *
 * <p>Registered by {@link io.nxmatic.rke2lab.manifests.domain.HighAvailabilityDomainRegistrar}
 * (catalog ID {@code "high-availability"}).
 *
 * <h2>Related documentation</h2>
 *
 * <ul>
 *   <li><a href="../../../../../../../../../../docs/manifests-architecture.adoc">Manifests
 *       Architecture</a> — the unit model and synthesis flow.
 * </ul>
 */
@org.jspecify.annotations.NullMarked
package io.nxmatic.rke2lab.manifests.units.ha;
