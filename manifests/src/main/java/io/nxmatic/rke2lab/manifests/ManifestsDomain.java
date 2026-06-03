// @codebase
package io.nxmatic.rke2lab.manifests;

import io.nxmatic.rke2lab.cdk8s.systemd.SystemdChart;
import java.util.List;

/**
 * A manifest domain groups related ManifestsUnits and optionally synthesizes systemd support units.
 *
 * <p>Domains correspond to installer services (e.g., "cluster-api" →
 * rke2lab-cluster-api-manifests.service).
 */
public class ManifestsDomain {
  private final String domainId;
  private final List<String> dependsOnDomainIds;
  private final List<? extends ManifestsUnit> units;

  public ManifestsDomain(
      String domainId, List<String> dependsOnDomainIds, List<? extends ManifestsUnit> units) {
    if (domainId == null || domainId.isBlank()) {
      throw new IllegalArgumentException("Domain id must not be blank");
    }
    if (dependsOnDomainIds == null) {
      throw new IllegalArgumentException("Domain dependencies must not be null: " + domainId);
    }
    if (units == null || units.isEmpty()) {
      throw new IllegalArgumentException("Domain must define at least one unit: " + domainId);
    }
    this.domainId = domainId;
    this.dependsOnDomainIds = List.copyOf(dependsOnDomainIds);
    this.units = List.copyOf(units);
  }

  public ManifestsDomain(String domainId, List<? extends ManifestsUnit> units) {
    this(domainId, List.of(), units);
  }

  public String domainId() {
    return domainId;
  }

  public List<String> dependsOnDomainIds() {
    return dependsOnDomainIds;
  }

  public List<? extends ManifestsUnit> units() {
    return units;
  }

  /**
   * Synthesizes systemd units for this domain.
   *
   * <p>Default implementation delegates to each ManifestsUnit's {@link
   * ManifestsUnit#synthesizeSystemdUnits}. Domains can also emit their own installer service that
   * installs all manifests in this domain.
   *
   * @param systemdChart the systemd chart to populate
   * @param context systemd synthesis context (contains references to common targets)
   */
  public void synthesizeSystemdUnits(SystemdChart systemdChart, SystemdSynthesisContext context) {
    // Delegate to each manifest unit
    for (ManifestsUnit unit : units) {
      unit.synthesizeSystemdUnits(systemdChart, context);
    }
  }
}
