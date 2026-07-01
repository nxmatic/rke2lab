package io.nxmatic.rke2lab.doctor.internal;

import io.nxmatic.rke2lab.doctor.records.Intervention;
import io.nxmatic.rke2lab.doctor.records.InterventionLedger;
import io.nxmatic.rke2lab.world.gateway.codec.DocumentCodec;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.InterventionWire;
import java.util.ArrayList;
import java.util.List;

/**
 * Folds the host {@link io.nxmatic.rke2lab.doctor.port.InterventionJournal}'s {@code intervention}
 * {@link Document}s into an {@link InterventionLedger} INSIDE the bundle realm, via {@link
 * InterventionReader} — the twin of {@link MedicalRecordReader}. Each Document carries ONE {@link
 * InterventionWire} (one ledger history entry = one intervention); this reader decodes it with the
 * realm's {@link DocumentCodec} and rebuilds one {@link Intervention}. A malformed entry degrades
 * to being skipped (the tolerant contract of {@link InterventionReader}); the ledger never throws.
 */
public final class InterventionLedgerReader {

  private final DocumentCodec codec = new DocumentCodec();

  public InterventionLedger read(List<Document> journal) {
    final List<Intervention> interventions = new ArrayList<>();
    for (Document entry : journal) {
      final InterventionWire wire;
      try {
        wire = codec.decode(entry, InterventionWire.class);
      } catch (RuntimeException e) {
        // A malformed envelope is degraded, not fatal: the ledger folds the readable entries. The
        // host journal already propagated genuine stack corruption before producing a Document.
        continue;
      }
      InterventionReader.fromWire(wire).ifPresent(interventions::add);
    }
    return new InterventionLedger(interventions);
  }
}
