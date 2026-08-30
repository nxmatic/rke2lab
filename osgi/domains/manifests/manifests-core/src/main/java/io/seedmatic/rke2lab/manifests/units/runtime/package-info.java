// @codebase
/**
 * Runtime domain manifest units: node runtime configuration and the flox NRI plugin delivery.
 *
 * <p>Unlike the other domains, runtime is split into focused sub-packages:
 *
 * <ul>
 *   <li>{@code rke2} — {@code RuntimeRke2ConfigManifestsUnit}, the RKE2 server config (installed
 *       <b>before</b> rke2-server).
 *   <li>{@code flox} — the flox-runtime delivery units: {@code FloxControllerManifestsUnit}
 *       (node-agent + CRDs), {@code FloxCatalogManifestsUnit} (the catalog Flux source), {@code
 *       FloxEnvManifestsUnit} (workload {@code FloxEnv} CRs), {@code FloxWebhookManifestsUnit}.
 * </ul>
 *
 * <p>Registered by {@link io.seedmatic.rke2lab.manifests.domain.RuntimeDomainRegistrar}.
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
@org.jspecify.annotations.NullMarked
package io.seedmatic.rke2lab.manifests.units.runtime;
