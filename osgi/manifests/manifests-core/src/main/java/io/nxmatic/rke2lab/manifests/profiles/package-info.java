// @codebase
/**
 * Immutable context records: the orthogonal slices of runtime configuration that {@link
 * io.nxmatic.rke2lab.manifests.ManifestSynthesisContext} publishes to units during synthesis.
 *
 * <h2>Context slices</h2>
 *
 * <ul>
 *   <li>{@link io.nxmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity} — cluster + node
 *       identity (clusterName, clusterId, clusterToken, incusRemoteName, …).
 *   <li>{@link io.nxmatic.rke2lab.manifests.contract.profiles.ComponentVersions} — version pins
 *       (cilium, flux, cluster-api operators, …).
 *   <li>{@link io.nxmatic.rke2lab.manifests.contract.profiles.NetworkTopology} — CIDRs, interface
 *       names, gateway addresses.
 *   <li>{@link io.nxmatic.rke2lab.manifests.contract.profiles.FloxDebugPolicy} — per-domain flox
 *       NRI debug toggle.
 *   <li>{@link io.nxmatic.rke2lab.manifests.contract.profiles.ImageState} — Stage A → Stage B
 *       control-node image identity (alias, fingerprint, checksum, remote).
 * </ul>
 *
 * <p>Also here: {@link io.nxmatic.rke2lab.manifests.profiles.PackageMetadataProfile}, which stamps
 * the {@code io.nxmatic.rke2lab/package} annotations the exploder routes on, and the Delve/flox
 * sidecar and runtime-pod profiles.
 *
 * <p>Units read these via the helper accessors on {@link
 * io.nxmatic.rke2lab.manifests.AbstractManifestsUnit} (e.g. {@code bootstrapIdentity()}, {@code
 * componentVersions()}), never by holding a reference directly.
 *
 * <h2>Related documentation</h2>
 *
 * <ul>
 *   <li><a href="../../../../../../../../../docs/bootstrap-identity-provider.adoc">Bootstrap
 *       Identity Provider</a> — the context-access pattern these records support.
 *   <li><a href="../../../../../../../../../docs/manifests-architecture.adoc">Manifests
 *       Architecture</a> — the synthesis flow that binds the context.
 * </ul>
 */
package io.nxmatic.rke2lab.manifests.profiles;
