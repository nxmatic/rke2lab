// @codebase
/**
 * Domain registrars: each implementation maps one manifest domain (networking, gitops, …) to its
 * ordered list of {@link io.nxmatic.rke2lab.manifests.ManifestsUnit}s.
 *
 * <h2>Pattern</h2>
 *
 * <p>Every registrar implements {@link io.nxmatic.rke2lab.manifests.ManifestsDomainRegistrar} and
 * builds a {@link io.nxmatic.rke2lab.manifests.ManifestsDomain} from catalog-derived IDs and direct
 * unit instantiation:
 *
 * <pre>{@code
 * new ManifestsDomain(
 *     ManifestDomainCatalog.GITOPS,
 *     List.of(ManifestDomainCatalog.PLATFORM),
 *     List.of(new FluxOperatorManifestsUnit()));
 * }</pre>
 *
 * <p>Domain IDs always come from {@link
 * io.nxmatic.rke2lab.manifests.contract.ManifestDomainCatalog}, never string literals — this
 * prevents the kebab-case/camelCase mismatches documented in the catalog pattern.
 *
 * <h2>Status note</h2>
 *
 * <p>These registrars exist and follow the current pattern, but the service does not yet wire them
 * up: {@code DefaultManifestSynthesisService.buildDomainRegistry} throws {@code
 * UnsupportedOperationException}. The policy-aware {@code domain(ManifestDomainPolicy)} hook is
 * defined on the interface but no registrar overrides it today.
 *
 * <h2>Related documentation</h2>
 *
 * <ul>
 *   <li><a href="../../../../../../../../../docs/manifest-conditional-inclusion.adoc">Manifest
 *       Conditional Inclusion</a> — policy-driven optional units (intended design).
 *   <li><a
 *       href="../../../../../../../../../docs/manifest-domain-catalog-pattern.adoc">ManifestDomainCatalog
 *       Pattern</a> — single source of truth for the domain IDs used here.
 *   <li><a href="../../../../../../../../../docs/manifests-architecture.adoc">Manifests
 *       Architecture</a> — how domains fit the synthesis flow.
 * </ul>
 */
package io.nxmatic.rke2lab.manifests.domain;
