package io.seedmatic.rke2lab.cluster.contract;

/**
 * The cluster's readiness at convergence — the two facts the checkpoint's phases read, produced by
 * a single {@link ClusterReadinessContact#awaitReady} call. The twin of the systemd snapshot: the
 * edge does the reach + convergence once, and the scenario's phase steps read the fields rather
 * than making a contact each.
 *
 * <ul>
 *   <li>{@code apiReady} — the kube-apiserver answered {@code /readyz=ok} within the connect
 *       budget. False means it never came up in time (the reach gave up).
 *   <li>{@code controllersEffective} — every required controller rolled out within the ready
 *       budget. Vacuously true when no controllers are required; false if API never came ready
 *       (nothing was awaited).
 * </ul>
 */
public record ClusterReadinessSnapshot(
    boolean apiReady, boolean controllersEffective, String summary) {

  /** API ready and every controller effective — the fully-converged cluster. */
  public static ClusterReadinessSnapshot ready() {
    return new ClusterReadinessSnapshot(true, true, "cluster ready");
  }

  /** The apiserver never answered {@code /readyz=ok} within the connect budget. */
  public static ClusterReadinessSnapshot apiNotReady(String summary) {
    return new ClusterReadinessSnapshot(false, false, summary);
  }

  /** API ready, but a required controller did not roll out within the ready budget. */
  public static ClusterReadinessSnapshot controllersNotEffective(String summary) {
    return new ClusterReadinessSnapshot(true, false, summary);
  }
}
