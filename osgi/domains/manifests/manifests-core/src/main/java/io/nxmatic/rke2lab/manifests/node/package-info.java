// @codebase
/**
 * NodeEnv SPI: per-domain contributions to the node environment, assembled into a {@link
 * io.nxmatic.rke2lab.manifests.port.node.NodeEnvContext}.
 *
 * <p>This is orthogonal to manifest synthesis. Where {@code ManifestsUnit}s emit K8s/systemd
 * artifacts, {@link io.nxmatic.rke2lab.manifests.port.node.NodeEnvContributor}s contribute
 * environment facts (identity, networking, storage, …) that the node needs at runtime.
 *
 * <h2>What lives here</h2>
 *
 * <ul>
 *   <li>{@link io.nxmatic.rke2lab.manifests.port.node.NodeEnvContext} / {@link
 *       io.nxmatic.rke2lab.manifests.node.DefaultNodeEnvContext} — the assembled context.
 *   <li>{@link io.nxmatic.rke2lab.manifests.port.node.NodeEnvContributor} — the contribution SPI.
 *   <li>{@link io.nxmatic.rke2lab.manifests.node.NodeEnvContributorRegistry} — collects all
 *       contributors.
 *   <li>{@link io.nxmatic.rke2lab.manifests.node.NodeEnvIdentityContributor} — the identity slice.
 * </ul>
 *
 * <p>Per-domain contributors live alongside their units (e.g. {@code
 * units.networking.NetworkingNodeEnvContributor}).
 *
 * <h2>Related documentation</h2>
 *
 * <ul>
 *   <li><a href="../../../../../../../../../docs/bootstrap-identity-provider.adoc">Bootstrap
 *       Identity Provider</a> — how cluster/node identity flows into context.
 *   <li><a href="../../../../../../../../../docs/manifests-architecture.adoc">Manifests
 *       Architecture</a> — the module this SPI belongs to.
 * </ul>
 */
@org.osgi.annotation.versioning.Version("1.0.0")
@org.jspecify.annotations.NullMarked
package io.nxmatic.rke2lab.manifests.node;
