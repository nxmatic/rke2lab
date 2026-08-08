package io.nxmatic.rke2lab.cluster.contract;

import io.nxmatic.rke2lab.osgi.runtime.readiness.ReadinessBudget;
import java.nio.file.Path;
import java.util.List;

/**
 * The cluster domain's external-contact seam: "await the live cluster converging to ready", read
 * over a published kubeconfig. The {@code cluster-edge} provides it through a typed fabric8 client;
 * the readiness checkpoint calls it once and reads the two facts of the returned snapshot as its
 * phases.
 *
 * <p>The wait lives in the edge now, bounded by the two-tier {@link ReadinessBudget}: the reach
 * retries {@code /readyz} until the connect deadline (a cold boot / fresh image re-seed lives
 * here), then — once the API answers — the controllers are awaited rolling out until the ready
 * deadline. This is where the deadline-poll the BDD migration had left one-shot now lives; the
 * checkpoint no longer owns a retry loop, only the policy→{@link ControllerRef} projection and the
 * phase reads.
 */
public interface ClusterReadinessContact {

  /**
   * Reach the kube-apiserver via {@code kubeconfig} and await the cluster converging to ready
   * within {@code budget}: retry {@code /readyz} until the connect deadline, then await every
   * controller in {@code controllers} rolling out until the ready deadline. Returns a {@link
   * ClusterReadinessSnapshot} carrying both facts — the API answered, and the controllers rolled
   * out (vacuously true for an empty list) — never throwing for a not-ready cluster (that is a
   * false facet in the snapshot, which the checkpoint reads).
   */
  ClusterReadinessSnapshot awaitReady(
      Path kubeconfig, List<ControllerRef> controllers, ReadinessBudget budget);
}
