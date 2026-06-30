package io.nxmatic.rke2lab.doctor.port;

import io.nxmatic.rke2lab.doctor.records.*;

/**
 * The write seam for the intervention ledger — the twin of the read seam {@link
 * io.nxmatic.rke2lab.pulumi.edge.StackHandle}. Implementations persist an {@link Intervention} to a
 * stack. The interface exists so tests can fake it without running Pulumi, and so the operator
 * command and the drift specialist depend on the contract, not on Pulumi internals.
 */
public interface InterventionLedgerWriter {

  /**
   * Append a single intervention to the ledger. Each append registers a new history entry in the
   * ledger stack.
   *
   * @param intervention the intervention to persist
   */
  void append(Intervention intervention);
}
