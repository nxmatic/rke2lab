package io.seedmatic.rke2lab.doctor.internal;

import io.seedmatic.rke2lab.doctor.contract.Intervention;
import io.seedmatic.rke2lab.doctor.contract.InterventionLedger;
import io.seedmatic.rke2lab.doctor.contract.InterventionWire;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.ArrayList;
import java.util.List;

/**
 * Folds the host {@link io.seedmatic.rke2lab.seed.broker.port.Cellar}'s {@code intervention} {@link
 * SeedEnvelope}s into an {@link InterventionLedger} INSIDE the bundle realm, via {@link
 * InterventionReader} — the twin of {@link MedicalRecordReader}. Each SeedEnvelope carries ONE
 * {@link InterventionWire} (one ledger history entry = one intervention); this reader decodes it
 * with the realm's {@link SeedCodec} and rebuilds one {@link Intervention}. A malformed entry
 * degrades to being skipped (the tolerant contract of {@link InterventionReader}); the ledger never
 * throws.
 */
public final class InterventionLedgerReader {

  private final SeedCodec codec = new SeedCodec();

  public InterventionLedger read(List<SeedEnvelope> journal) {
    final List<Intervention> interventions = new ArrayList<>();
    for (SeedEnvelope entry : journal) {
      final InterventionWire wire;
      try {
        wire = codec.decode(entry, InterventionWire.class);
      } catch (RuntimeException e) {
        // A malformed envelope is degraded, not fatal: the ledger folds the readable entries. The
        // host journal already propagated genuine stack corruption before producing a SeedEnvelope.
        continue;
      }
      InterventionReader.fromWire(wire).ifPresent(interventions::add);
    }
    return new InterventionLedger(interventions);
  }
}
