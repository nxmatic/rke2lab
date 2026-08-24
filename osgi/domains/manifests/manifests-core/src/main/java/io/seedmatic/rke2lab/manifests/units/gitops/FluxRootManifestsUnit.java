package io.seedmatic.rke2lab.manifests.units.gitops;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotations;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * The chicken-and-egg solver for the rendered-branch GitOps model: the bootstrap objects — a {@code
 * GitRepository} on THIS cluster's rendered branch plus a layered {@code Kustomization} stack —
 * that, once seeded from the node's local-only server-manifests, let Flux self-manage everything
 * else from the branch (see {@code docs/architecture/cluster-api/manifests-rendered-branches.adoc}
 * §layers and {@code github-credential-model.adoc}).
 *
 * <p><b>GitRepository:</b> {@code https://github.com/seedmatic/rke2lab.git} (HTTPS — App auth, not
 * SSH), tracking {@code ref.branch: manifests/<host>-<role>} (this cluster's rendered branch, the
 * EXACT branch the manifests scion pushes — {@link
 * io.seedmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity#clusterSlug()}). It
 * authenticates as the one org-owned GitHub App via {@code spec.provider: github} + {@code
 * spec.secretRef} → the {@code githubapp} Secret ({@code githubAppID}, {@code
 * githubAppInstallationID}, {@code githubAppPrivateKey}); Flux self-mints and self-refreshes a
 * {@code contents:read} pull token. The {@code provider: github} is mandatory — source-controller
 * rejects an App-data secret without it.
 *
 * <p><b>Layered Kustomizations:</b> one per reconcile layer ({@code ./crds} → {@code ./operators} →
 * {@code ./workloads}), chained by {@code dependsOn} with {@code wait: true}, each pruned and
 * SOPS-decrypting via the {@code sops-age} Secret — so a CR dry-runs only once its CRD (rendered in
 * {@code crds}, or registered at runtime by an operator/installer in {@code operators}) exists. The
 * two Secrets (App-auth + age) ride the same local-only bootstrap lane so Flux comes up able to
 * both pull and decrypt.
 */
public final class FluxRootManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.GITOPS + "/flux-root";

  private static final String REPO_URL = "https://github.com/seedmatic/rke2lab.git";
  private static final String BRANCH_PREFIX = "manifests/";
  private static final String GIT_REPOSITORY_NAME = "rke2lab";
  private static final String APP_AUTH_SECRET = "githubapp";
  private static final String SOPS_AGE_SECRET = "sops-age";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("gitops", "flux-root", true);

  public FluxRootManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of(FluxInstanceManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final String clusterSlug = ManifestSynthesisContext.current().bootstrapIdentity().clusterSlug();
    createGitRepository(scope, clusterSlug);
    // The layered stack: crds → operators → workloads, chained by dependsOn (+wait) so a CR's CRD —
    // rendered (crds) or registered at runtime by an operator/installer (operators) — exists before
    // the CR is dry-run. See manifests-rendered-branches.adoc §layers.
    createLayerKustomization(scope, clusterSlug, ManifestAnnotations.LAYER_CRDS, Optional.empty());
    createLayerKustomization(
        scope,
        clusterSlug,
        ManifestAnnotations.LAYER_OPERATORS,
        Optional.of(ManifestAnnotations.LAYER_CRDS));
    createLayerKustomization(
        scope,
        clusterSlug,
        ManifestAnnotations.LAYER_WORKLOADS,
        Optional.of(ManifestAnnotations.LAYER_OPERATORS));
  }

  private void createGitRepository(Construct scope, String clusterSlug) {
    final ApiObject gitRepo =
        new ApiObject(
            scope,
            "gitrepository-rke2lab",
            ApiObjectProps.builder()
                .apiVersion("source.toolkit.fluxcd.io/v1")
                .kind("GitRepository")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(GIT_REPOSITORY_NAME)
                        .namespace("flux-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "source.toolkit.fluxcd.io|GitRepository|flux-system|"
                                    + GIT_REPOSITORY_NAME))
                        .build())
                .build());

    gitRepo.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "interval",
                "1m",
                "url",
                REPO_URL,
                "ref",
                Map.of("branch", BRANCH_PREFIX + clusterSlug),
                // GitHub App auth REQUIRES provider=github; without it source-controller rejects
                // the
                // App-data secret ("has github app data but provider is not set to github").
                "provider",
                "github",
                "secretRef",
                Map.of("name", APP_AUTH_SECRET))));
  }

  /**
   * One layer's {@code Kustomization} over {@code ./<layer>} — pruned, waited (Ready only when its
   * objects are healthy) and SOPS-decrypting. {@code dependsOnLayer} chains it after the prior
   * layer ({@code Optional.empty()} for the first, {@code crds}), so its CRs dry-run only once the
   * earlier layer's CRDs — rendered or operator-registered — exist.
   */
  private void createLayerKustomization(
      Construct scope, String clusterSlug, String layer, Optional<String> dependsOnLayer) {
    final String name = clusterSlug + "-" + layer;
    final ApiObject kustomization =
        new ApiObject(
            scope,
            "kustomization-" + layer,
            ApiObjectProps.builder()
                .apiVersion("kustomize.toolkit.fluxcd.io/v1")
                .kind("Kustomization")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(name)
                        .namespace("flux-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "kustomize.toolkit.fluxcd.io|Kustomization|flux-system|" + name))
                        .build())
                .build());

    final LinkedHashMap<String, Object> spec = new LinkedHashMap<>();
    spec.put("interval", "5m");
    spec.put("path", "./" + layer);
    spec.put("prune", true);
    spec.put("wait", true);
    spec.put("sourceRef", Map.of("kind", "GitRepository", "name", GIT_REPOSITORY_NAME));
    spec.put(
        "decryption", Map.of("provider", "sops", "secretRef", Map.of("name", SOPS_AGE_SECRET)));
    dependsOnLayer.ifPresent(
        dep -> spec.put("dependsOn", List.of(Map.of("name", clusterSlug + "-" + dep))));

    kustomization.addJsonPatch(JsonPatch.add("/spec", spec));
  }
}
