package io.nxmatic.rke2lab.cluster.port;

/**
 * The single source of truth for the cluster domain's reasoning schemas — the {@code
 * "cluster/*∕v1"} coordinates the {@code ClusterSpecialist} carries on its {@code Assessment}s.
 * Promoting the loose literals to typed constants here, in the cluster port, removes the name-drift
 * hazard {@code SystemdUnitId} cured for systemd: a consumer references the constant, never
 * re-spells the string, so producer and consumer cannot disagree.
 */
public enum ClusterSchemaRef {
  KUBECONFIG("cluster/kubeconfig/v1"),
  CONTROLLER("cluster/controller/v1"),
  API("cluster/api/v1"),
  OTHER("cluster/other/v1");

  private final String id;

  ClusterSchemaRef(String id) {
    this.id = id;
  }

  /**
   * The schema identifier string an {@link io.nxmatic.rke2lab.doctor.records.Assessment}
   * references.
   */
  public String id() {
    return id;
  }
}
