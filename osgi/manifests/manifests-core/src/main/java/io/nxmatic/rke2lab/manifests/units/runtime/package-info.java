// @codebase
/**
 * Runtime domain manifest units: node runtime configuration and the flox NRI plugin delivery.
 *
 * <p>Unlike the other domains, runtime is split into focused sub-packages:
 *
 * <ul>
 *   <li>{@code rke2} — {@code RuntimeRke2ConfigManifestsUnit}, the RKE2 server config (installed
 *       <b>before</b> rke2-server).
 *   <li>{@code env} — {@code RKE2LabEnvConfigManifestsUnit} and the runtime {@link
 *       io.nxmatic.rke2lab.manifests.contract.node.NodeEnvContributor}.
 *   <li>{@code flox} — {@code FloxRuntimeManifestsUnit} and the NRI plugin archive assets.
 *   <li>{@code daemonset} — the daemonset script-policy unit that trampolines host assets.
 *   <li>{@code cloudinit} — {@code CloudConfigManifestsUnit} for cloud-init delivery.
 *   <li>{@code libexec} — the systemd libexec placeholder unit.
 * </ul>
 *
 * <p>{@link io.nxmatic.rke2lab.manifests.units.runtime.RuntimeRefs} holds the shared resource
 * references. Registered by {@link io.nxmatic.rke2lab.manifests.domain.RuntimeDomainRegistrar}.
 *
 * <h2>Related documentation</h2>
 *
 * <ul>
 *   <li><a href="../../../../../../../../../../docs/manifests-architecture.adoc">Manifests
 *       Architecture</a> — the unit model and synthesis flow.
 *   <li><a
 *       href="../../../../../../../../../../docs/daemonset-host-assets-architecture.adoc">DaemonSet
 *       Host Assets Architecture</a> — the daemonset-to-host asset trampoline.
 * </ul>
 */
package io.nxmatic.rke2lab.manifests.units.runtime;
