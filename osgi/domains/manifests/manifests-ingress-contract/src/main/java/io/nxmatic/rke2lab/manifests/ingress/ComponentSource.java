package io.nxmatic.rke2lab.manifests.ingress;

import java.util.Optional;

/**
 * The upstream provenance of a bumpable {@link Component}: the GitHub repository whose releases pin
 * it and — for the components whose version ALSO drives a vendored upstream manifest — the release
 * asset name and the {@code manifests-core} resource dir that asset is dropped into. A pure value
 * record co-located with {@link Component} so the whole "what is this component and where does its
 * version come from" truth lives in ONE place; the {@code versions} bumper reads it to query GitHub
 * and refresh the vendored asset.
 *
 * @param githubRepo the {@code owner/repo} whose releases pin the component
 * @param releaseAssetName the release asset the vendored manifest is fetched from (e.g. {@code
 *     operator-components.yaml}), empty when the component vendors no upstream manifest
 * @param vendoredResourceDir the {@code manifests-core} resource dir the asset is dropped into,
 *     relative to {@code src/main/resources} (e.g. {@code upstream/clusterapi/operator}), empty
 *     when none — the file itself is {@code release-<version>.yaml}
 */
public record ComponentSource(
    String githubRepo, Optional<String> releaseAssetName, Optional<String> vendoredResourceDir) {

  /** Whether this component's version drives a vendored {@code release-<version>.yaml} asset. */
  public boolean hasUpstreamYaml() {
    return releaseAssetName.isPresent() && vendoredResourceDir.isPresent();
  }
}
