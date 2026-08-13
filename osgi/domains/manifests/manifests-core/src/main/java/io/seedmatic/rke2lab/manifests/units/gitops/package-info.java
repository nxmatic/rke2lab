// @codebase
/**
 * GitOps domain manifest units: Flux operator, instance, root sync, and the SOPS age secret.
 *
 * <ul>
 *   <li>{@link io.seedmatic.rke2lab.manifests.units.gitops.FluxOperatorManifestsUnit} — installs
 *       the Flux operator.
 *   <li>{@link io.seedmatic.rke2lab.manifests.units.gitops.FluxInstanceManifestsUnit} — the Flux
 *       instance (depends on the operator).
 *   <li>{@link io.seedmatic.rke2lab.manifests.units.gitops.FluxRootManifestsUnit} — the root
 *       Kustomization/GitRepository that bootstraps cluster-managed state.
 *   <li>{@link io.seedmatic.rke2lab.manifests.units.gitops.SopsAgeSecretManifestsUnit} — the age
 *       key secret used to decrypt SOPS-encrypted manifests.
 * </ul>
 *
 * <p>Registered by {@link io.seedmatic.rke2lab.manifests.domain.GitopsDomainRegistrar}. This is the
 * domain the conditional-inclusion doc uses for its (currently dormant, illustrative) optional-unit
 * example.
 *
 * <h2>Related documentation</h2>
 *
 * <ul>
 *   <li><a href="../../../../../../../../../../docs/manifest-conditional-inclusion.adoc">Manifest
 *       Conditional Inclusion</a> — policy-driven optional units within this domain.
 *   <li><a href="../../../../../../../../../../docs/manifests-architecture.adoc">Manifests
 *       Architecture</a> — the unit model and synthesis flow.
 * </ul>
 */
@org.jspecify.annotations.NullMarked
package io.seedmatic.rke2lab.manifests.units.gitops;
