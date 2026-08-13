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
 * Manifest unit that creates the Flux GitRepository and root Kustomization for GitOps bootstrap.
 *
 * <p>This is the chicken-and-egg solver for GitOps bootstrap:
 *
 * <ol>
 *   <li>The manifest layer emits GitRepository + Kustomization CRs during master bootstrap
 *   <li>Applied by {@code rke2lab-cluster-manifests.service} at first boot
 *   <li>From then on, Flux self-manages reconciliation of the {@code gitops/} subtree
 *   <li>The same CRs are also committed under {@code gitops/flux-system/} for self-reference
 * </ol>
 *
 * <p><b>GitRepository CR:</b> Points to this repository, branch {@code main}
 *
 * <p><b>Root Kustomization CR:</b> Watches {@code gitops/clusters/<cluster>/} in the GitRepository.
 * Configured with SOPS decryption using the cluster age key.
 *
 * <p><b>Note:</b> Currently uses placeholder values for repository URL and age key secret
 * reference. These will be configured from environment/config in a future iteration.
 */
public final class FluxRootManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.GITOPS + "/flux-root";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("gitops", "flux-root");

  public FluxRootManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of(FluxInstanceManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final String clusterName = ManifestSynthesisContext.current().bootstrapIdentity().clusterName();
    createGitRepository(scope, clusterName);
    createRootKustomization(scope, clusterName);
  }

  private void createGitRepository(Construct scope, String clusterName) {
    ApiObject gitRepo =
        new ApiObject(
            scope,
            "gitrepository-rke2lab",
            ApiObjectProps.builder()
                .apiVersion("source.toolkit.fluxcd.io/v1")
                .kind("GitRepository")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("rke2lab")
                        .namespace("flux-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "source.toolkit.fluxcd.io|GitRepository|flux-system|rke2lab"))
                        .build())
                .build());

    // TODO: Repository URL should be configurable (from environment or config)
    gitRepo.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "interval", "1m",
                "ref", Map.of("branch", "main"),
                "url", "https://github.com/nxmatic/rke2lab.git")));
  }

  private void createRootKustomization(Construct scope, String clusterName) {
    ApiObject kustomization =
        new ApiObject(
            scope,
            "kustomization-cluster",
            ApiObjectProps.builder()
                .apiVersion("kustomize.toolkit.fluxcd.io/v1")
                .kind("Kustomization")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(clusterName + "-cluster")
                        .namespace("flux-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "kustomize.toolkit.fluxcd.io|Kustomization|flux-system|"
                                    + clusterName
                                    + "-cluster"))
                        .build())
                .build());

    // TODO: SOPS age key secret reference should be dynamic once age key bootstrap is implemented
    kustomization.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "interval",
                "5m",
                "path",
                "./gitops/clusters/" + clusterName,
                "prune",
                true,
                "sourceRef",
                Map.of("kind", "GitRepository", "name", "rke2lab"),
                "decryption",
                Map.of("provider", "sops", "secretRef", Map.of("name", "sops-age")))));
  }
}
