package io.seedmatic.rke2lab.manifests;

import java.util.List;

/**
 * The post-resolve state of a {@link ManifestsDomainRegistry}: the only type that exposes a visit
 * order. It is reachable solely through {@link ManifestsDomainRegistry#resolve()}, which is the
 * single coherence gate — so a caller cannot obtain an order without the closure having been
 * resolved and checked. Immutable; it offers no way to re-resolve.
 */
public final class CoherentManifestsDomainRegistry {

  private final ManifestsDomainRegistry assembled;
  private final List<ManifestsUnit> visitOrder;

  CoherentManifestsDomainRegistry(
      final ManifestsDomainRegistry assembled, final List<String> orderedUnitIds) {
    this.assembled = assembled;
    final ManifestsUnitRegistry unitsById = new ManifestsUnitRegistry(assembled.manifestUnits());
    this.visitOrder = orderedUnitIds.stream().map(unitsById::requireById).toList();
  }

  /** The manifest units in resolved order: a unit follows every unit it depends on. */
  public List<ManifestsUnit> visitOrder() {
    return visitOrder;
  }

  public String requireDomainIdForManifestsUnit(final String manifestUnitId) {
    return assembled.requireDomainIdForManifestsUnit(manifestUnitId);
  }
}
