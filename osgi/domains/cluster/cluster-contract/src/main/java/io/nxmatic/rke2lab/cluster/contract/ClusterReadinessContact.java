package io.nxmatic.rke2lab.cluster.contract;

import java.nio.file.Path;
import java.util.List;

/**
 * The cluster domain's external-contact seam: point-in-time facts about a live cluster, read over a
 * published kubeconfig. The {@code cluster-edge} provides it by shelling {@code kubectl}; the
 * host's readiness probe composes it for the two kubectl-backed phases (API readiness, controller
 * effectiveness) and projects the booleans into doctor {@code Observation}s itself.
 *
 * <p>The grain is deliberately fine and stateless: each method answers ONE question about the
 * cluster as it is NOW. The contact owns no retry loop, no timeout policy, no phase ordering —
 * those are the host's orchestration, kept whole in one place. An edge "makes the contact and
 * returns a raw fact"; it does not diagnose, and it carries no host type — it sees only a
 * kubeconfig path and the already-projected {@link ControllerRef}s.
 */
public interface ClusterReadinessContact {

  /**
   * Whether the kube-apiserver reachable via {@code kubeconfig} reports {@code /readyz=ok} right
   * now. A single contact, no waiting — the host owns the wait loop.
   */
  boolean isApiReady(Path kubeconfig);

  /**
   * Whether every controller in {@code controllers} currently exists and is rolled out, checked via
   * {@code kubeconfig}. An empty list is vacuously effective. A single contact, no waiting — the
   * host owns the wait loop and the policy→{@link ControllerRef} projection.
   */
  boolean areControllersEffective(Path kubeconfig, List<ControllerRef> controllers);
}
