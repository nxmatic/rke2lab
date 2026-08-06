package io.nxmatic.rke2lab.systemd.bdd;

import io.nxmatic.rke2lab.systemd.contract.SystemdStatusSnapshot;

/**
 * A systemd readiness facet is not ready. An {@link AssertionError} (so jGiven marks the step
 * FAILED) that carries the whole {@link SystemdStatusSnapshot} as a typed member rather than
 * flattening it into the message: the message stays human-readable (facet + the snapshot's own
 * {@code summary()}), while any consumer that catches this — a doctor specialist, the error-
 * surfacing enricher — reads the full structured snapshot (the mandatory target and its state, the
 * failed units, the connection context) off {@link #snapshot()} instead of re-parsing a string.
 */
public final class SystemdNotReadyError extends AssertionError {

  // AssertionError is Serializable; the snapshot rides only in-realm (the runbook carries the
  // message + the ObservationWire), so it does not need to survive serialization.
  private final transient SystemdStatusSnapshot snapshot;

  public SystemdNotReadyError(String facet, SystemdStatusSnapshot snapshot) {
    super(facet + ": not ready — " + snapshot.summary());
    this.snapshot = snapshot;
  }

  public SystemdStatusSnapshot snapshot() {
    return snapshot;
  }
}
