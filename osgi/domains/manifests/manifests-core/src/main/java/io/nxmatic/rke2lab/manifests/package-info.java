// @codebase
/**
 * Manifest synthesis SPI: turns context-aware domain definitions into <b>both</b> Kubernetes
 * manifests and systemd unit files in a single pass.
 *
 * <h2>What lives here</h2>
 *
 * <ul>
 *   <li>{@link io.nxmatic.rke2lab.manifests.ManifestsUnit} — the atomic build block; a CDK8s {@code
 *       Construct} that synthesizes one logical group of K8s resources and (optionally) the systemd
 *       installer service for them.
 *   <li>{@link io.nxmatic.rke2lab.manifests.AbstractManifestsUnit} — base class wiring a unit into
 *       the CDK8s construct tree.
 *   <li>{@link io.nxmatic.rke2lab.manifests.ManifestsDomain} — groups related units and declares
 *       cross-domain dependency ordering.
 *   <li>{@link io.nxmatic.rke2lab.manifests.DefaultManifestSynthesisService} — the orchestrator:
 *       creates the {@code App}, the K8s {@code Chart} and the {@code SystemdChart}, then writes
 *       both output trees.
 *   <li>{@link io.nxmatic.rke2lab.manifests.ManifestSynthesisContext} — ThreadLocal runtime
 *       configuration injected into units for the duration of one synthesis.
 *   <li>{@link io.nxmatic.rke2lab.manifests.port.ManifestDomainCatalog} — single source of truth
 *       for domain ID strings (kebab-case).
 * </ul>
 *
 * <h2>Sub-packages</h2>
 *
 * <ul>
 *   <li>{@code domain} — the {@link io.nxmatic.rke2lab.manifests.ManifestsDomainRegistrar}
 *       implementations, one per concern.
 *   <li>{@code units.<domain>} — the concrete {@code *ManifestsUnit} classes.
 *   <li>{@code node} — the NodeEnv contributor SPI (node-environment, orthogonal to synthesis).
 *   <li>{@code profiles} — immutable context records ({@code BootstrapIdentity}, {@code
 *       ComponentVersions}, …).
 *   <li>{@code systemd} — bootstrap/infrastructure systemd synthesis.
 * </ul>
 *
 * <h2>Status note</h2>
 *
 * The registrar→registry path is currently dormant: {@code
 * DefaultManifestSynthesisService.buildDomainRegistry} throws {@code
 * UnsupportedOperationException}. See the architecture doc's "domain registry status".
 *
 * <h2>Related documentation</h2>
 *
 * <ul>
 *   <li><a href="../../../../../../../../docs/manifests-architecture.adoc">Manifests
 *       Architecture</a> — canonical reference for this module.
 *   <li><a href="../../../../../../../../docs/manifest-apply-flow.adoc">Manifest Apply Flow</a> —
 *       the runtime journey from synthesis to live resources.
 * </ul>
 */
@org.osgi.annotation.versioning.Version("1.0.0")
// SPEC_COVERAGE at WARN — visible backlog: a manifests-architecture doc exists but coverage is not
// yet enforced type-by-type. Drop once the exported types are specified. INSTANCE_DISCIPLINE is
// back
// at the ERROR-locked default: the static ManifestYaml + ManifestSynthesisContext#bind are gone
// (the
// YamlMapper @Component, and bind() now an instance method).
@GovernedBy(value = StagingGate.SPEC_COVERAGE, level = EnforcementLevel.WARN)
package io.nxmatic.rke2lab.manifests;

import io.nxmatic.rke2lab.domain.annotations.EnforcementLevel;
import io.nxmatic.rke2lab.domain.annotations.GovernedBy;
import io.nxmatic.rke2lab.domain.annotations.StagingGate;
