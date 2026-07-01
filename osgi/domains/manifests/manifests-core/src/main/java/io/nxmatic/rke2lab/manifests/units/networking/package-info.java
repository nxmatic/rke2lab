// @codebase
/**
 * Networking domain manifest units: the cluster CNI and edge networking.
 *
 * <ul>
 *   <li>{@link io.nxmatic.rke2lab.manifests.units.networking.CiliumConfigManifestsUnit} — Cilium
 *       HelmChartConfig + clustermesh ConfigMap; its installer runs <b>before</b> rke2-server.
 *   <li>{@link io.nxmatic.rke2lab.manifests.units.networking.CiliumAdvancedManifestsUnit} —
 *       advanced Cilium resources (BGP, L2 announcements, …).
 *   <li>{@link io.nxmatic.rke2lab.manifests.units.networking.KdnsManifestsUnit} — KDns deployment.
 *   <li>{@link io.nxmatic.rke2lab.manifests.units.networking.EnvoyGatewayManifestsUnit} — Envoy
 *       Gateway.
 *   <li>{@link io.nxmatic.rke2lab.manifests.units.networking.NetworkingNodeEnvContributor} —
 *       networking slice of the node environment.
 * </ul>
 *
 * <p>Registered by {@link io.nxmatic.rke2lab.manifests.domain.NetworkingDomainRegistrar}.
 *
 * <h2>Related documentation</h2>
 *
 * <ul>
 *   <li><a href="../../../../../../../../../../docs/manifests-architecture.adoc">Manifests
 *       Architecture</a> — the unit model and synthesis flow.
 *   <li><a href="../../../../../../../../../../docs/cilium-bgp-multi-host-topology.adoc">Cilium BGP
 *       Multi-Host Topology</a> — networking design for multi-node clusters.
 * </ul>
 */
@org.jspecify.annotations.NullMarked
package io.nxmatic.rke2lab.manifests.units.networking;
