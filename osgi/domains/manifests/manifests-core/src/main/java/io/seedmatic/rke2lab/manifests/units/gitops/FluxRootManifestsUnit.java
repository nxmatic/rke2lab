package io.seedmatic.rke2lab.manifests.units.gitops;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * The chicken-and-egg solver for the rendered-branch GitOps model: the single bootstrap object — a
 * {@code GitRepository} on THIS cluster's rendered branch plus a root {@code Kustomization} — that,
 * once seeded from the node's local-only server-manifests, lets Flux self-manage everything else
 * from the branch (see {@code docs/architecture/cluster-api/manifests-rendered-branches.adoc} and
 * {@code github-credential-model.adoc}).
 *
 * <p><b>GitRepository:</b> {@code https://github.com/seedmatic/rke2lab.git} (HTTPS — App auth, not
 * SSH), tracking {@code ref.branch: manifests/<host>-<role>} (this cluster's rendered branch, the
 * EXACT branch the manifests scion pushes — {@link
 * io.seedmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity#clusterSlug()}). It
 * authenticates as the one org-owned GitHub App via {@code spec.secretRef} → the {@code githubapp}
 * Secret ({@code githubAppID}, {@code githubAppInstallationID}, {@code githubAppPrivateKey}); Flux
 * self-mints and self-refreshes a {@code contents:read} pull token.
 *
 * <p><b>Root Kustomization:</b> watches the branch root ({@code path: ./}) with {@code prune: true}
 * and SOPS decryption via the {@code sops-age} Secret — the two Secrets (App-auth + age) ride the
 * same local-only bootstrap lane so Flux comes up able to both pull and decrypt.
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
    createRootKustomization(scope, clusterSlug);
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
                "secretRef",
                Map.of("name", APP_AUTH_SECRET))));
  }

  private void createRootKustomization(Construct scope, String clusterSlug) {
    final ApiObject kustomization =
        new ApiObject(
            scope,
            "kustomization-root",
            ApiObjectProps.builder()
                .apiVersion("kustomize.toolkit.fluxcd.io/v1")
                .kind("Kustomization")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(clusterSlug)
                        .namespace("flux-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "kustomize.toolkit.fluxcd.io|Kustomization|flux-system|"
                                    + clusterSlug))
                        .build())
                .build());

    kustomization.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "interval",
                "5m",
                "path",
                "./",
                "prune",
                true,
                "sourceRef",
                Map.of("kind", "GitRepository", "name", GIT_REPOSITORY_NAME),
                "decryption",
                Map.of("provider", "sops", "secretRef", Map.of("name", SOPS_AGE_SECRET)))));
  }
}
