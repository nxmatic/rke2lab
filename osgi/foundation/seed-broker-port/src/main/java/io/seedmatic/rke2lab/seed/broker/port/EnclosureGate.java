package io.seedmatic.rke2lab.seed.broker.port;

import java.util.function.Supplier;

/**
 * The ambient execution ENCLOSURE, published ONCE as a service the whole run shares: is this
 * process the OPERATOR (the operator's host — {@code .secrets} + the ndh key inventory are at hand)
 * or IN_CLUSTER (a Kubernetes pod — the git worktree is sops-encrypted at rest and secrets arrive
 * mounted from the cluster's own Secrets)? Like {@link RunGate}, it is a fact of the WHOLE run, not
 * a property of any one seed, so it is an ambient service rather than a value on an envelope.
 *
 * <p>The sibling of {@link RunGate} (survey vs cultivate) but ORTHOGONAL to it: a process can be
 * OPERATOR while surveying, or IN_CLUSTER on a live run. The host projects it at boot from its
 * {@code ExecutionEnvironment} (which resolves the enclosure from the ambient environment); no host
 * type crosses — the scion consumes only this seam interface, exactly as it does {@link RunGate}.
 *
 * <p>Consumed by the SCION as the deterministic gate on enclosure-dependent logic: the manifests
 * render reads the ndh key-store (for {@code sops-age} and the commit-signing key) ONLY as
 * OPERATOR; IN_CLUSTER it skips the store (already-bootstrapped {@code sops-age}) and reveals the
 * signing key from a mounted Secret. The gate NAMES that fork rather than inferring it from an
 * empty projection.
 *
 * <p>A mechanism, not an orchestrator: it answers {@link #inCluster()} and offers one combinator so
 * the scion expresses "operator branch, or in-cluster branch" without branching on a bare boolean.
 * See docs/architecture/cluster-api/pac-in-cluster-render-spec.adoc (the auth section).
 */
public interface EnclosureGate {

  /**
   * Whether this process runs inside a Kubernetes pod (IN_CLUSTER) rather than on the operator
   * host.
   */
  boolean inCluster();

  /**
   * Cross the fork: run {@code inCluster} when in a pod, {@code operator} on the operator host. The
   * scion supplies both branches; the gate chooses. Neither branch is evaluated until chosen.
   */
  default <T> T through(Supplier<T> operator, Supplier<T> inCluster) {
    return inCluster() ? inCluster.get() : operator.get();
  }
}
