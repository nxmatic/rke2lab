// @codebase
/**
 * Mesh domain manifest units: the service-mesh / VPN layer (Tailscale + Headscale + Headplane).
 *
 * <ul>
 *   <li>{@link io.seedmatic.rke2lab.manifests.units.mesh.MeshSystemNamespaceManifestsUnit} — the
 *       shared namespace the other mesh units depend on.
 *   <li>{@link io.seedmatic.rke2lab.manifests.units.mesh.HeadscaleManifestsUnit} — Headscale
 *       control server.
 *   <li>{@link io.seedmatic.rke2lab.manifests.units.mesh.HeadplaneManifestsUnit} — Headplane UI.
 *   <li>{@link io.seedmatic.rke2lab.manifests.units.mesh.TailscaleManifestsUnit} — Tailscale
 *       operator + connector.
 * </ul>
 *
 * <p>Registered by {@link io.seedmatic.rke2lab.manifests.domain.MeshDomainRegistrar}.
 *
 * <h2>Related documentation</h2>
 *
 * <ul>
 *   <li><a href="../../../../../../../../../../docs/manifests-architecture.adoc">Manifests
 *       Architecture</a> — the unit model and synthesis flow.
 * </ul>
 */
@org.jspecify.annotations.NullMarked
package io.seedmatic.rke2lab.manifests.units.mesh;
