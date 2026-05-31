// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import io.nxmatic.rke2lab.cdk8s.systemd.SystemdChart;
import java.util.List;

/**
 * A manifest domain groups related ManifestUnits and optionally synthesizes systemd support units.
 *
 * <p>Domains correspond to installer services (e.g., "cluster-api" →
 * rke2lab-cluster-api-manifests.service).
 */
public class LayerDomain {
  private final String domainId;
  private final List<String> dependsOnDomainIds;
  private final List<? extends ManifestUnit> layers;

  public LayerDomain(
      String domainId, List<String> dependsOnDomainIds, List<? extends ManifestUnit> layers) {
    if (domainId == null || domainId.isBlank()) {
      throw new IllegalArgumentException("Domain id must not be blank");
    }
    if (dependsOnDomainIds == null) {
      throw new IllegalArgumentException("Domain dependencies must not be null: " + domainId);
    }
    if (layers == null || layers.isEmpty()) {
      throw new IllegalArgumentException("Domain must define at least one layer: " + domainId);
    }
    this.domainId = domainId;
    this.dependsOnDomainIds = List.copyOf(dependsOnDomainIds);
    this.layers = List.copyOf(layers);
  }

  public LayerDomain(String domainId, List<? extends ManifestUnit> layers) {
    this(domainId, List.of(), layers);
  }

  public String domainId() {
    return domainId;
  }

  public List<String> dependsOnDomainIds() {
    return dependsOnDomainIds;
  }

  public List<? extends ManifestUnit> layers() {
    return layers;
  }

  /**
   * Synthesizes systemd units for this domain.
   *
   * <p>Default implementation delegates to each ManifestUnit's {@link
   * ManifestUnit#synthesizeSystemdUnits}. Domains can also emit their own installer service that
   * installs all manifests in this domain.
   *
   * @param systemdChart the systemd chart to populate
   */
  public void synthesizeSystemdUnits(SystemdChart systemdChart) {
    // Delegate to each manifest unit
    for (ManifestUnit unit : layers) {
      unit.synthesizeSystemdUnits(systemdChart);
    }
  }
}
