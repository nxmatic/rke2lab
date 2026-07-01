// @codebase
/**
 * Bootstrap and infrastructure systemd synthesis: the units that must exist <i>around</i> the
 * per-manifest installer services emitted by {@code ManifestsUnit.synthesizeSystemdUnits}.
 *
 * <h2>What lives here</h2>
 *
 * <ul>
 *   <li>{@link io.nxmatic.rke2lab.manifests.systemd.BootstrapInfrastructureSynthesizer} — creates
 *       the bootstrap/infrastructure services (e.g. {@code rke2lab-install}) that domain installer
 *       services depend on; runs before the domain loop in the synthesis service. Hosts the
 *       method-local staged-synthesis pipeline (see the fluent pipeline grammar).
 *   <li>{@link io.nxmatic.rke2lab.manifests.systemd.SystemdUnitSynthesizer} — the synthesis
 *       contract.
 * </ul>
 *
 * <p>The staged-synthesis harness (topic runner + failure type) is the shared {@code
 * io.nxmatic.rke2lab.pipeline} grammar (pipeline-core), consumed in-bundle here.
 *
 * <p>The systemd target hierarchy itself (rke2lab, -network, -tools, -bootstrap, -manifests,
 * -secrets) and the rke2-server drop-in are created in {@link
 * io.nxmatic.rke2lab.manifests.DefaultManifestSynthesisService}.
 *
 * <h2>Related documentation</h2>
 *
 * <ul>
 *   <li><a href="../../../../../../../../../docs/systemd-architecture.adoc">Systemd
 *       Architecture</a> — target hierarchy, ordering directives, drop-in mechanism.
 *   <li><a href="../../../../../../../../../docs/manifests-architecture.adoc">Manifests
 *       Architecture</a> — how systemd synthesis pairs with K8s synthesis.
 *   <li><a href="../../../../../../../../../docs/fluent-pipeline-grammar.adoc">Fluent Pipeline
 *       Grammar</a> — the staged-synthesis pattern used here.
 * </ul>
 */
@org.jspecify.annotations.NullMarked
package io.nxmatic.rke2lab.manifests.systemd;
