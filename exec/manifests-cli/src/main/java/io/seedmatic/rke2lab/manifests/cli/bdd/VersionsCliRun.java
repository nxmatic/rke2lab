package io.seedmatic.rke2lab.manifests.cli.bdd;

import io.seedmatic.rke2lab.manifests.ingress.BumpLevel;
import io.seedmatic.rke2lab.manifests.ingress.Component;
import java.util.Optional;

/**
 * The driver-captured bump policy the {@code versions} verb seeds into {@link VersionsCliScenario}
 * — TYPED, because {@link BumpLevel}/{@link Component} live in the dual-realm ingress module the
 * flat host compiles against: the level ceiling, whether to APPLY (else report), and an optional
 * single-{@link Component} filter. The host fills these onto the bump facet by slug at the amend
 * door, naming no manifests-contract type. Absent component = every component.
 */
public record VersionsCliRun(BumpLevel level, boolean apply, Optional<Component> component) {

  public static VersionsCliRun of(BumpLevel level, boolean apply, Optional<Component> component) {
    return new VersionsCliRun(level, apply, component);
  }
}
