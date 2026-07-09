package io.nxmatic.rke2lab.doctor.port;

import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;

/**
 * The write seam for the intervention ledger — the twin of the read seam {@link
 * io.nxmatic.rke2lab.pulumi.edge.StackHandle}. Implementations persist a canonical {@code
 * intervention} {@link SeedEnvelope} to a stack. The interface exists so tests can fake it without
 * running Pulumi, and so the operator command and the drift specialist depend on the contract, not
 * on Pulumi internals.
 *
 * <p>The crossing carries a neutral {@link SeedEnvelope}, never a doctor record: the canonical
 * shape is owned OSGi-side (the intervention ingress SeedHandler, {@code DriftSpecialist} for the
 * inferred path) and serialized into the SeedEnvelope's {@code payload}; the host writer
 * deserializes it with its own jackson and persists the map. No doctor type and no jackson type
 * cross this seam.
 */
public interface InterventionLedgerWriter {

  /**
   * Append a single intervention to the ledger. Each append registers a new history entry in the
   * ledger stack.
   *
   * @param intervention the canonical {@code intervention} SeedEnvelope to persist (its payload is
   *     the flat {@code Intervention.toOutputMap} shape, serialized JSON)
   */
  void append(SeedEnvelope intervention);
}
