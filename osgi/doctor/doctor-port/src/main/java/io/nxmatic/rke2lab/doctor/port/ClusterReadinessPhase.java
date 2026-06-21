package io.nxmatic.rke2lab.doctor.port;

/**
 * The canonical-order phases of the cluster-readiness checkpoint — the checks the real {@code
 * ClusterBootstrapReadinessVerifier} runs once the systemd-adapter dependency is satisfied. Modeled
 * as discrete phases so each is a runbook step with its own injectable probe, and so an ordered
 * fake incident can target a single phase.
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
