package io.nxmatic.rke2lab.doctor.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the aggregator contract of {@link MedicalRecordReader}: a per-entry fold that fails AT END
 * (partial record + suppressed identity-enriched failures), never fail-fast and never
 * log-and-swallow. Empty timeline is genuine nothing-here (empty record, no exception). The CALLER
 * decides what to do with the partial — the reader only carries it.
 */
class MedicalRecordReaderTest {

  private static final Patient PATIENT = new Patient("organization", "rke2lab", "dev");

  /**
   * In-test seam: a timeline plus, per entry, either a snapshot to return or an exception to throw
   * from {@code at(...)}. No mock framework — a fake honours the instance-passing discipline.
   */
  private static final class FakeSnapshotSource implements SnapshotSource {

    private final List<SnapshotEntry> timeline = new ArrayList<>();
    private final Map<SnapshotEntry, SnapshotView> snapshots = new LinkedHashMap<>();
    private final Map<SnapshotEntry, SnapshotContentException> contentFailures =
        new LinkedHashMap<>();
    private SnapshotException timelineFailure;

    FakeSnapshotSource readable(SnapshotEntry entry, SnapshotView snapshot) {
      timeline.add(entry);
      snapshots.put(entry, snapshot);
      return this;
    }

    FakeSnapshotSource failing(SnapshotEntry entry, SnapshotContentException failure) {
      timeline.add(entry);
      contentFailures.put(entry, failure);
      return this;
    }

    FakeSnapshotSource timelineUnreadable(SnapshotException failure) {
      this.timelineFailure = failure;
      return this;
    }

    @Override
    public List<SnapshotEntry> timeline() throws SnapshotAccessException, SnapshotContentException {
      if (timelineFailure instanceof SnapshotAccessException access) {
        throw access;
      }
      if (timelineFailure instanceof SnapshotContentException content) {
        throw content;
      }
      return List.copyOf(timeline);
    }

    @Override
    public SnapshotView at(SnapshotEntry entry)
        throws SnapshotAccessException, SnapshotContentException {
      final SnapshotContentException failure = contentFailures.get(entry);
      if (failure != null) {
        throw failure;
      }
      return snapshots.get(entry);
    }

    @Override
    public Optional<SnapshotView> latest() {
      if (timeline.isEmpty()) {
        return Optional.empty();
      }
      return Optional.ofNullable(snapshots.get(timeline.get(timeline.size() - 1)));
    }
  }

  private static SnapshotEntry entry(int version) {
    return new SnapshotEntry(version, Instant.ofEpochSecond(version));
  }

  /** The location string the fake stamps onto a per-entry content failure for {@code v}. */
  private static String location(int version) {
    return "v" + version + ".json";
  }

  /**
   * A view carrying exactly one consultationReport output that round-trips through {@link
   * ConsultationReportReader} into a real {@link ConsultationReport} for {@code symptom}.
   * outputsNamed returns the per-resource list of values, so one resource → a singleton list.
   */
  private static SnapshotView snapshotOf(Symptom symptom) {
    final Map<String, Object> plan = new LinkedHashMap<>();
    plan.put(Symptom.ENVELOPE_KEY, symptom.id());
    plan.put("generalistSummary", "s");
    plan.put("prescriptions", List.of());
    final Map<String, Object> report = new LinkedHashMap<>();
    report.put("checkpointId", "systemd-adapter");
    report.put("observations", List.of());
    report.put("plan", plan);
    return new SnapshotView(Map.of(ConsultationReport.OUTPUT_KEY, List.of(report)));
  }

  /** A readable snapshot that raised no consultationReport — the healthy run. */
  private static SnapshotView emptySnapshot() {
    return new SnapshotView(Map.of());
  }

  @Test
  void read_twoReadableEntries_returnsUsableRecord() throws Exception {
    final SnapshotEntry v1 = entry(1);
    final SnapshotEntry v2 = entry(2);
    final SnapshotSource source =
        new FakeSnapshotSource()
            .readable(v1, snapshotOf(Symptom.TIMEOUT))
            .readable(v2, snapshotOf(Symptom.CONNECTION_REFUSED));

    final MedicalRecord record = new MedicalRecordReader(source).read(PATIENT);

    assertEquals(PATIENT, record.patient());
    assertEquals(2, record.visits().size());
    assertEquals(1, record.visits().get(0).version());
    assertEquals(v1.when(), record.visits().get(0).when());
    assertEquals(2, record.visits().get(1).version());
    assertEquals(v2.when(), record.visits().get(1).when());

    // End-to-end: the fold produced a record the clinical views can actually answer.
    final ChiefComplaint current = record.chiefComplaint();
    assertEquals(1, current.reports().size());
    assertEquals(Symptom.CONNECTION_REFUSED, current.reports().get(0).symptom());
    assertEquals(1, record.historyOf(Symptom.TIMEOUT).occurrences().size());
    assertEquals(1, record.historyOf(Symptom.CONNECTION_REFUSED).occurrences().size());
  }

  @Test
  void read_emptyTimeline_returnsEmptyRecordWithoutThrowing() throws Exception {
    final SnapshotSource source = new FakeSnapshotSource();

    final MedicalRecord record = new MedicalRecordReader(source).read(PATIENT);

    assertEquals(PATIENT, record.patient());
    assertTrue(record.visits().isEmpty());
  }

  @Test
  void read_readableEntryWithNoConsultationReport_yieldsVisitWithEmptyReports() throws Exception {
    final SnapshotEntry v1 = entry(1);
    final SnapshotSource source = new FakeSnapshotSource().readable(v1, emptySnapshot());

    final MedicalRecord record = new MedicalRecordReader(source).read(PATIENT);

    // A healthy run is a visit that happened, not a failure and not a skipped entry.
    assertEquals(1, record.visits().size());
    assertTrue(record.visits().get(0).reports().isEmpty());
    assertTrue(record.chiefComplaint().isEmpty());
  }

  @Test
  void read_unreadableTimeline_throwsWithEmptyPartialAndTheLeafSuppressed() {
    final SnapshotAccessException leaf =
        new SnapshotAccessException("history", new java.io.IOException("dir unreadable"));
    final SnapshotSource source = new FakeSnapshotSource().timelineUnreadable(leaf);

    final MedicalRecordReconstructionException ex =
        assertThrows(
            MedicalRecordReconstructionException.class,
            () -> new MedicalRecordReader(source).read(PATIENT));

    // No spine = no partial to build: an empty record, and the leaf itself suppressed (it already
    // carries location() — no entry identity to add).
    assertTrue(ex.partialRecord().visits().isEmpty());
    assertEquals(1, ex.getSuppressed().length);
    assertSame(leaf, ex.getSuppressed()[0]);
  }

  @Test
  void read_middleEntryFails_throwsWithPartialRecordAndIdentityEnrichedSuppressed() {
    final SnapshotEntry v1 = entry(1);
    final SnapshotEntry v2 = entry(2);
    final SnapshotEntry v3 = entry(3);
    final SnapshotContentException leaf =
        new SnapshotContentException(location(2), new IllegalStateException("broken checkpoint"));
    final SnapshotSource source =
        new FakeSnapshotSource()
            .readable(v1, snapshotOf(Symptom.TIMEOUT))
            .failing(v2, leaf)
            .readable(v3, snapshotOf(Symptom.CONNECTION_REFUSED));

    final MedicalRecordReconstructionException ex =
        assertThrows(
            MedicalRecordReconstructionException.class,
            () -> new MedicalRecordReader(source).read(PATIENT));

    // Partial: the two readable visits survive, the failed one does not.
    final MedicalRecord partial = ex.partialRecord();
    assertEquals(2, partial.visits().size());
    assertEquals(List.of(1, 3), partial.visits().stream().map(Visit::version).toList());

    // Suppressed: exactly one, identity-enriched (knows WHICH entry), cause is the leaf with its
    // location.
    assertEquals(1, ex.getSuppressed().length);
    final Throwable suppressed = ex.getSuppressed()[0];
    final MedicalRecordReconstructionException.EntryFailure failure =
        assertInstanceOf(MedicalRecordReconstructionException.EntryFailure.class, suppressed);
    assertEquals(2, failure.version());
    assertEquals(v2.when(), failure.when());
    assertSame(leaf, failure.getCause());
    assertEquals(location(2), ((SnapshotException) failure.getCause()).location());
  }

  @Test
  void read_twoEntriesFail_suppressesBothAndKeepsTheRest() {
    final SnapshotEntry v1 = entry(1);
    final SnapshotEntry v2 = entry(2);
    final SnapshotEntry v3 = entry(3);
    final SnapshotSource source =
        new FakeSnapshotSource()
            .failing(v1, new SnapshotContentException(location(1), new IllegalStateException("a")))
            .readable(v2, snapshotOf(Symptom.TIMEOUT))
            .failing(v3, new SnapshotContentException(location(3), new IllegalStateException("b")));

    final MedicalRecordReconstructionException ex =
        assertThrows(
            MedicalRecordReconstructionException.class,
            () -> new MedicalRecordReader(source).read(PATIENT));

    assertEquals(2, ex.getSuppressed().length);
    final MedicalRecord partial = ex.partialRecord();
    assertEquals(1, partial.visits().size());
    assertEquals(2, partial.visits().get(0).version());
    assertFalse(partial.visits().isEmpty());
  }
}
