package io.nxmatic.rk2lab.manifests.layers.gitops;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.common.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.Chart;
import org.cdk8s.JsonPatch;

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
public final class FluxRootManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = "gitops/flux-root";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("gitops", "flux-root");

  public FluxRootManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of(FluxInstanceManifestUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public void apply(final Chart chart) {
    final String clusterName = bootstrapIdentity().clusterName();

    createGitRepository(chart, clusterName);
    createRootKustomization(chart, clusterName);
  }

  private void createGitRepository(Chart chart, String clusterName) {
    ApiObject gitRepo =
        new ApiObject(
            chart,
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

  private void createRootKustomization(Chart chart, String clusterName) {
    ApiObject kustomization =
        new ApiObject(
            chart,
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
