package io.seedmatic.rke2lab.manifests.bdd.versions;

import io.seedmatic.rke2lab.manifests.ingress.Component;
import java.util.Optional;

/**
 * One row of the {@code versions} bumper report — the DISCOVERED state of a single {@link
 * Component}: what it is pinned to, what its upstream latest release is, and — under the authorised
 * {@link io.seedmatic.rke2lab.manifests.ingress.BumpLevel} gate — the highest release it may bump
 * to right now. Keyed by the typed {@link Component} (not a loose id); discovery is computed
 * OSGi-side by the bumper, never by the passive enum.
 *
 * @param component the component this row reports on
 * @param currentPin the component's baseline pin ({@link Component#defaultVersion()}), verbatim
 * @param upstreamLatest the highest semver release found upstream, empty if unreachable / no source
 * @param allowedTarget the highest release reachable within the level gate and strictly above the
 *     current pin, empty when already current or when nothing within the gate is newer
 * @param note a human note when there is no clean numeric answer (non-GitHub source, unreachable,
 *     unparseable pin); empty otherwise
 */
public record VersionReport(
    Component component,
    String currentPin,
    Optional<SemanticVersion> upstreamLatest,
    Optional<SemanticVersion> allowedTarget,
    String note) {

  public VersionReport {
    note = note == null ? "" : note;
  }

  static VersionReport manual(
      final Component component, final String currentPin, final String why) {
    return new VersionReport(component, currentPin, Optional.empty(), Optional.empty(), why);
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
