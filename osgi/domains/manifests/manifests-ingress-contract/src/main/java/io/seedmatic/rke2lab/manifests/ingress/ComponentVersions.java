// @codebase
package io.seedmatic.rke2lab.manifests.ingress;

import java.util.EnumMap;
import java.util.Map;

/**
 * The resolved bootstrap-layer component versions — the manifests domain's dual-realm host face,
 * threaded through {@code ManifestSynthesisRequest}/{@code ManifestSynthesisContext} and read by
 * every manifest unit as {@code componentVersions().of(Component.X)}. A thin holder over {@link
 * Component}: the single source of truth for a component's identity, provenance and DEFAULT pin is
 * the {@link Component} enum; this record is the resolved snapshot the synthesis reads.
 *
 * <p>{@link #defaults()} is the all-components-pinned baseline, built from each {@link
 * Component#defaultVersion()} — the enum constant literal the {@code versions} bumper rewrites in
 * place. A future Pulumi per-component override layer ({@code rke2lab:components.<slug>.version})
 * would resolve on top HERE; it is deliberately absent until wired (no speculative machinery).
 */
public record ComponentVersions(Map<Component, String> pins) {

  public ComponentVersions {
    pins = pins == null ? Map.of() : Map.copyOf(pins);
  }

  /** The baseline: every {@link Component} at its {@link Component#defaultVersion()}. */
  public static ComponentVersions defaults() {
    final Map<Component, String> baseline = new EnumMap<>(Component.class);
    for (final Component component : Component.values()) {
      baseline.put(component, component.defaultVersion());
    }
    return new ComponentVersions(baseline);
  }

  /** The resolved version pinned for {@code component}; fail-fast if none is present. */
  public String of(final Component component) {
    final String version = pins.get(component);
    if (version == null || version.isBlank()) {
      throw new IllegalStateException("no version pinned for component " + component.slug());
    }
    return version;
  }
}
