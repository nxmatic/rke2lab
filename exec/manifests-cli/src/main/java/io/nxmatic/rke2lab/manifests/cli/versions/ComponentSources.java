package io.nxmatic.rke2lab.manifests.cli.versions;

import java.util.List;
import java.util.Optional;

/**
 * Provenance catalog for the {@code manifests versions} bumper: maps each {@link
 * io.nxmatic.rke2lab.manifests.ingress.ComponentVersions} component id to the GitHub repository
 * whose releases pin it, and — for the components whose version ALSO drives a vendored upstream
 * manifest — the release asset name and the resource directory that asset is dropped into.
 *
 * <p>This is a *dev-tool* companion, deliberately kept in {@code manifests-cli} rather than in the
 * {@code manifests-ingress-contract} carrier: it carries no runtime contract (the runtime reads
 * only the version strings from {@code ComponentVersions}), and living outside the exported carrier
 * keeps it clear of the spec-coverage staging gate. {@code ComponentVersions} stays the single
 * source of truth for the versions themselves; this only records *where each one comes from* so the
 * bumper can query GitHub for newer releases and, on {@code apply}, refresh the vendored asset.
 *
 * <p>Only GitHub-release-sourced components are listed. Chart-repo / container-registry sources
 * (openebs-zfs chart, alpine/k8s docker tag) are intentionally absent — the bumper reports them as
 * {@code manual} until a non-GitHub source kind is added.
 *
 * @param componentId the {@code ComponentVersions} record-component name (== the id used here)
 * @param githubRepo the {@code owner/repo} whose releases pin this component
 * @param releaseAssetName the release asset the vendored manifest is fetched from (e.g. {@code
 *     operator-components.yaml}), or {@code null} when this component vendors no upstream manifest
 * @param vendoredResourceDir the {@code manifests-core} resource dir the asset is dropped into,
 *     relative to {@code src/main/resources} (e.g. {@code upstream/clusterapi/operator}), or {@code
 *     null} when none — the file itself is {@code release-<version>.yaml}
 */
public record ComponentSources(
    String componentId, String githubRepo, String releaseAssetName, String vendoredResourceDir) {

  /** Whether this component's version drives a vendored {@code release-<version>.yaml} asset. */
  public boolean hasUpstreamYaml() {
    return releaseAssetName != null && !releaseAssetName.isBlank();
  }

  /** The GitHub-sourced components. */
  public static List<ComponentSources> github() {
    return List.of(
        source("capiCore", "kubernetes-sigs/cluster-api"),
        vendored(
            "clusterApiOperator",
            "kubernetes-sigs/cluster-api-operator",
            "operator-components.yaml",
            "upstream/clusterapi/operator"),
        source("capiIncusProvider", "lxc/cluster-api-provider-incus"),
        source("capiRke2Provider", "rancher/cluster-api-provider-rke2"),
        vendored(
            "tektonOperator", "tektoncd/operator", "release.yaml", "upstream/cicd/tekton-operator"),
        source("kubeVip", "kube-vip/kube-vip"),
        source("certManager", "cert-manager/cert-manager"),
        source("envoyGateway", "envoyproxy/gateway"),
        source("tailscale", "tailscale/tailscale"),
        source("fluxOperator", "controlplaneio-fluxcd/flux-operator"),
        source("kubernetesReplicator", "mittwald/kubernetes-replicator"));
  }

  private static ComponentSources source(final String componentId, final String githubRepo) {
    return new ComponentSources(componentId, githubRepo, null, null);
  }

  private static ComponentSources vendored(
      final String componentId,
      final String githubRepo,
      final String releaseAssetName,
      final String vendoredResourceDir) {
    return new ComponentSources(componentId, githubRepo, releaseAssetName, vendoredResourceDir);
  }

  public static Optional<ComponentSources> byId(final String componentId) {
    return github().stream().filter(s -> s.componentId().equals(componentId)).findFirst();
  }
}
