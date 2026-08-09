package io.nxmatic.rke2lab.manifests.cli.versions;

import java.util.Optional;

/**
 * One row of the {@code versions} bumper report: what a single component is pinned to, what its
 * upstream latest release is, and — under the authorised {@link SemanticVersion.Level} gate — the
 * highest release the operator is allowed to bump to right now.
 *
 * @param componentId the {@code ComponentVersions} record-component name (== {@code
 *     ComponentSources} id), e.g. {@code capiCore}
 * @param currentPin the version string as pinned in {@code ComponentVersions.defaults()} (verbatim,
 *     leading {@code v} kept)
 * @param upstreamLatest the highest semver release found upstream, empty if the source was
 *     unreachable or unparseable
 * @param allowedTarget the highest release reachable within the level gate and strictly above the
 *     current pin, empty when already current or when nothing within the gate is newer
 * @param note a human note when there is no clean numeric answer (non-GitHub source, unreachable,
 *     unparseable pin); empty otherwise
 */
public record VersionReport(
    String componentId,
    String currentPin,
    Optional<SemanticVersion> upstreamLatest,
    Optional<SemanticVersion> allowedTarget,
    String note) {

  public VersionReport {
    note = note == null ? "" : note;
  }

  static VersionReport manual(final String componentId, final String currentPin, final String why) {
    return new VersionReport(componentId, currentPin, Optional.empty(), Optional.empty(), why);
  }

  /** True when a bump within the authorised gate is available. */
  public boolean bumpAvailable() {
    return allowedTarget.isPresent();
  }

  /** True when a newer release exists upstream but is held back by the level gate. */
  public boolean heldByGate() {
    return upstreamLatest.isPresent()
        && allowedTarget.isEmpty()
        && SemanticVersion.parse(currentPin)
            .map(current -> upstreamLatest.get().compareTo(current) > 0)
            .orElse(false);
  }
}
