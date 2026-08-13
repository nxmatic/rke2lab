// @codebase
/**
 * The node environment context. {@link io.seedmatic.rke2lab.manifests.node.DefaultNodeEnvContext}
 * assembles the run's {@link io.seedmatic.rke2lab.manifests.contract.node.NodeEnvContext} — the
 * cluster/node identity and network-topology views — from the handed-over identity, derived once
 * via the netplan blueprint and threaded to every unit at synthesis time.
 *
 * <h2>Related documentation</h2>
 *
 * <ul>
 *   <li><a href="../../../../../../../../../docs/bootstrap-identity-provider.adoc">Bootstrap
 *       Identity Provider</a> — how cluster/node identity flows into context.
 *   <li><a href="../../../../../../../../../docs/manifests-architecture.adoc">Manifests
 *       Architecture</a> — the module this context belongs to.
 * </ul>
 */
@org.osgi.annotation.versioning.Version("1.0.0")
@org.jspecify.annotations.NullMarked
package io.seedmatic.rke2lab.manifests.node;
