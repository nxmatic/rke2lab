package io.nxmatic.rke2lab.doctor.internal;

import io.nxmatic.rke2lab.doctor.records.ConsultationReport;
import io.nxmatic.rke2lab.doctor.records.Expectation;
import io.nxmatic.rke2lab.doctor.records.MedicalRecord;
import io.nxmatic.rke2lab.doctor.records.Visit;
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
 * lists. This reader decodes the payload with doctor-core's OWN jackson (via {@link DocumentCodec},
 * no jackson type crosses the seam) and rebuilds the typed visit by decoding each opaque blob
 * directly into its record ({@link ConsultationReport} / {@link Expectation}).
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

    // The consultationReport blobs are one report map per resource: decode each directly via the
    // codec. A structurally-invalid blob throws (a record's compact-ctor guard) — caught here to
    // Optional.empty and dropped, so a malformed report degrades WITHOUT sinking the visit (the
    // additive-tolerance the hand-rolled readers had).
    final List<ConsultationReport> reports =
        visit.consultationReport().stream()
            .map(blob -> decodeBlob(blob, ConsultationReport.class))
            .flatMap(Optional::stream)
            .toList();
    // The expectations output was registered as Output.of(List<Map>), so the harvested blob is a
    // list-of-lists (one inner list per resource). Flatten the inner lists before decoding — the
    // same shape the SnapshotView fold consumed.
    final List<Expectation> expectations =
        visit.expectations().stream()
            .filter(List.class::isInstance)
            .flatMap(perResource -> ((List<?>) perResource).stream())
            .map(blob -> decodeBlob(blob, Expectation.class))
            .flatMap(Optional::stream)
            .toList();
    return new Visit(visit.version(), visit.when(), reports, expectations);
  }

  /**
   * Decode one opaque blob to a typed record, degrading a malformed blob to {@link Optional#empty}
   * rather than sinking the whole visit. This is the finer-grained tolerance the deleted {@code
   * *Reader} classes gave (a bad report/expectation is dropped, its siblings survive); the
   * entry-level fold in {@link #read} degrades the whole visit only when the {@code VisitWire}
   * envelope itself is unreadable.
   */
  private <T> Optional<T> decodeBlob(Object blob, Class<T> type) {
    try {
      return Optional.ofNullable(codec.fromMap(blob, type));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
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
