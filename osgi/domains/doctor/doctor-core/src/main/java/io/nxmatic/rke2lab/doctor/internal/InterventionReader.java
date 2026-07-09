package io.nxmatic.rke2lab.doctor.internal;

import io.nxmatic.rke2lab.doctor.records.Intervention;
import io.nxmatic.rke2lab.doctor.records.ProblemRef;
import io.nxmatic.rke2lab.doctor.records.Provenance;
import io.nxmatic.rke2lab.doctor.records.RemediationProgramRef;
import io.nxmatic.rke2lab.seed.broker.port.InterventionWire;
import java.util.Optional;

/**
 * The tolerant inverse of {@link InterventionDocuments}: rebuilds a typed {@link Intervention} from
 * an {@link InterventionWire} (the flat wire shape a ledger entry registered). It never throws —
 * malformed input yields {@link Optional#empty()} — so a stale or partially-written intervention
 * degrades instead of crashing the read.
 *
 * <p>Additive by design: the wire's raw string refs are parsed into the doctor vocabulary here (the
 * OSGi side owns it); an unparseable required ref degrades the whole entry to empty. The wire's
 * {@code details} bag is carried through, so a key the producer adds tomorrow survives a round-trip
 * through a reader written today.
 */
public final class InterventionReader {

  private InterventionReader() {}

  /**
   * Three refs are HARD requirements: {@code provenance} (must parse to a valid {@link
   * Provenance}), {@code problem} (must parse to a valid {@link ProblemRef}); {@code when} is a
   * typed {@link java.time.Instant} on the wire. The {@code prescriptionRef} is optional — absence
   * or an unparseable value yields {@link Optional#empty()}. The {@code details} bag is carried
   * through unchanged.
   */
  public static Optional<Intervention> fromWire(InterventionWire wire) {
    if (wire == null) {
      return Optional.empty();
    }

    final Optional<Provenance> provenance = Provenance.parse(wire.provenance());
    if (provenance.isEmpty()) {
      return Optional.empty();
    }

    final Optional<ProblemRef> problem = ProblemRef.parse(wire.problem());
    if (problem.isEmpty()) {
      return Optional.empty();
    }

    final Optional<RemediationProgramRef> prescriptionRef =
        wire.prescriptionRef().flatMap(RemediationProgramRef::parse);

    return Optional.of(
        new Intervention(
            provenance.get(),
            wire.when(),
            wire.what(),
            problem.get(),
            prescriptionRef,
            wire.details()));
  }
}
