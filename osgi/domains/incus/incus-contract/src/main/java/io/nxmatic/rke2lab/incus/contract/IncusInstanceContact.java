package io.nxmatic.rke2lab.incus.contract;

import java.util.Optional;

/**
 * The incus domain's external-contact seam: point-in-time facts about a launched Incus instance,
 * reached over {@code ssh … incus exec}. The {@code incus-edge} provides it by shelling the
 * command; the host readiness gate composes it for the instance-reachability axis and owns the
 * retry loop, the deadline, and the {@code ObservationView} projection.
 *
 * <p>The grain is fine and stateless — one call asks ONE question about the instance as it is NOW.
 * The contact owns no waiting and no policy.
 */
public interface IncusInstanceContact {

  /**
   * Whether the instance named in {@code request} is reachable right now, tested by running a
   * trivial command on it over {@code ssh … incus exec}. Returns {@link Optional#empty()} when
   * reachable; otherwise a short human summary of why the contact failed (a non-zero exit, a
   * timeout, a spawn failure). Never throws for an unreachable instance — the host reads the reason
   * and decides.
   */
  Optional<String> isReachable(IncusExecRequest request);
}
