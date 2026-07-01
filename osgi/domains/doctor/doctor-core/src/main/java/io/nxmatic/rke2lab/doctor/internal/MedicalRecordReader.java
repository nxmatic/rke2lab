package io.nxmatic.rke2lab.doctor.internal;

import io.nxmatic.rke2lab.doctor.records.*;
import io.nxmatic.rke2lab.world.gateway.codec.DocumentCodec;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Patient;
import io.nxmatic.rke2lab.world.gateway.port.VisitWire;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reconstructs a {@link Patient}'s {@link MedicalRecord} by folding the host {@link
 * io.nxmatic.rke2lab.doctor.port.MedicalRecordJournal}'s {@code visit} Documents into one {@link
 * Visit} per readable entry — INSIDE the bundle realm, where the {@code doctor.records} types are
 * legal. Each Document's payload is the opaque blob the host produced WITHOUT interpreting it:
 * {@code version} + {@code when} plus the raw consultation-report and expectation output blob
 * lists. This reader parses the payload with doctor-core's OWN jackson (no jackson type crosses the
 * seam) and rebuilds the typed visit via {@link ConsultationReportReader}/{@link
 * ExpectationReader}.
 *
 * <p>The aggregator does fail-AT-END, not fail-fast: an unreadable Document is collected
 * (identity-enriched) and the fold continues; if any entry failed it throws a {@link
 * MedicalRecordReconstructionException} carrying the partial record plus one suppressed {@link
 * MedicalRecordReconstructionException.EntryFailure} per failure, leaving the policy decision to
 * the caller. An empty journal is nothing-here: an empty record, no exception.
 */
public final class MedicalRecordReader {

  private final DocumentCodec codec = new DocumentCodec();

  public MedicalRecord read(Patient patient, List<Document> journal)
      throws MedicalRecordReconstructionException {
    final List<Visit> visits = new ArrayList<>();
    final List<Throwable> failures = new ArrayList<>();

    for (Document entry : journal) {
      try {
        visits.add(visitOf(entry));
      } catch (EntryReadException e) {
        // Identity-enrichment: a subordinate decode failure does not decide policy; record the
        // failure and keep folding. A malformed payload cannot yield an identity (the codec throws
        // before the version/when are readable), so it degrades to the unknown-entry identity.
        failures.add(
            new MedicalRecordReconstructionException.EntryFailure(e.version, e.when, e.getCause()));
      }
    }

    final MedicalRecord partial = new MedicalRecord(patient, visits);
    if (failures.isEmpty()) {
      return partial;
    }
    throw failed(patient, partial, failures);
  }

  private Visit visitOf(Document entry) throws EntryReadException {
    final VisitWire visit;
    try {
      visit = codec.decode(entry, VisitWire.class);
    } catch (RuntimeException e) {
      throw new EntryReadException(0, null, e);
    }

    // The consultationReport blobs are one report map per resource: parse each directly.
    final List<ConsultationReport> reports =
        visit.consultationReport().stream()
            .map(ConsultationReportReader::fromOutputMap)
            .flatMap(Optional::stream)
            .toList();
    // The expectations output was registered as Output.of(List<Map>), so the harvested blob is a
    // list-of-lists (one inner list per resource). Flatten the inner lists before parsing — the
    // same shape the SnapshotView fold consumed.
    final List<Expectation> expectations =
        visit.expectations().stream()
            .filter(List.class::isInstance)
            .flatMap(perResource -> ((List<?>) perResource).stream())
            .map(ExpectationReader::fromOutputMap)
            .flatMap(Optional::stream)
            .toList();
    return new Visit(visit.version(), visit.when(), reports, expectations);
  }

  private static MedicalRecordReconstructionException failed(
      Patient patient, MedicalRecord partial, List<? extends Throwable> failures) {
    final MedicalRecordReconstructionException aggregate =
        new MedicalRecordReconstructionException(partial, failures.size());
    failures.forEach(aggregate::addSuppressed);
    return aggregate;
  }

  /** A per-entry read failure carrying the identity (version + when) the partial fold preserves. */
  private static final class EntryReadException extends Exception {
    private static final long serialVersionUID = 1L;
    private final int version;
    private final transient Instant when;

    EntryReadException(int version, Instant when, Throwable cause) {
      super(cause);
      this.version = version;
      this.when = when;
    }
  }
}
