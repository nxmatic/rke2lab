package io.seedmatic.rke2lab.cluster.contract;

/**
 * The canonical-order phases of the cluster-readiness checkpoint — the checks the host runs once
 * the systemd-adapter dependency is satisfied. Modeled as discrete phases so each is a runbook step
 * with its own injectable probe, and so an ordered fake incident can target a single phase.
 *
 * <p>It lives in the cluster domain's port, its owner — not in the doctor's records: the doctor has
 * no business carrying another domain's readiness vocabulary.
 */
public enum ClusterReadinessPhase {
  KUBECONFIG_PUBLISHED("kubeconfig published"),
  API_READY("kubernetes API ready"),
  CONTROLLERS_EFFECTIVE("required controllers effective");

  private final String label;

  ClusterReadinessPhase(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}
