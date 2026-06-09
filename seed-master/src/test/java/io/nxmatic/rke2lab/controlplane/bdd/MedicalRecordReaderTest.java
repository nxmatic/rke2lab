package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pulumi.automation.StackDeployment;
import io.nxmatic.rke2lab.pulumi.automation.StackAccessException;
import io.nxmatic.rke2lab.pulumi.automation.StackContentException;
import io.nxmatic.rke2lab.pulumi.automation.StackException;
import io.nxmatic.rke2lab.pulumi.automation.StackHistory;
import io.nxmatic.rke2lab.pulumi.automation.StackSnapshot;
import java.nio.file.Path;
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

    private final List<StackHistory.Entry> timeline = new ArrayList<>();
    private final Map<StackHistory.Entry, StackSnapshot> snapshots = new LinkedHashMap<>();
    private final Map<StackHistory.Entry, StackContentException> contentFailures =
        new LinkedHashMap<>();
    private StackException timelineFailure;

    FakeSnapshotSource readable(StackHistory.Entry entry, StackSnapshot snapshot) {
      timeline.add(entry);
      snapshots.put(entry, snapshot);
      return this;
    }

    FakeSnapshotSource failing(StackHistory.Entry entry, StackContentException failure) {
      timeline.add(entry);
      contentFailures.put(entry, failure);
      return this;
    }

    FakeSnapshotSource timelineUnreadable(StackException failure) {
      this.timelineFailure = failure;
      return this;
    }

    @Override
    public List<StackHistory.Entry> timeline() throws StackAccessException, StackContentException {
      if (timelineFailure instanceof StackAccessException access) {
        throw access;
      }
      if (timelineFailure instanceof StackContentException content) {
        throw content;
      }
      return List.copyOf(timeline);
    }

    @Override
    public StackSnapshot at(StackHistory.Entry entry)
        throws StackAccessException, StackContentException {
      final StackContentException failure = contentFailures.get(entry);
      if (failure != null) {
        throw failure;
      }
      return snapshots.get(entry);
    }

    @Override
    public Optional<StackSnapshot> latest() {
      if (timeline.isEmpty()) {
        return Optional.empty();
      }
      return Optional.ofNullable(snapshots.get(timeline.get(timeline.size() - 1)));
    }
  }

  private static StackHistory.Entry entry(int version) {
    return new StackHistory.Entry(
        version, Instant.ofEpochSecond(version), "succeeded", Path.of("v" + version + ".json"));
  }

  /**
   * A snapshot carrying exactly one consultationReport output that round-trips through {@link
   * DiagnosisReader} into a real {@link ConsultationReport} for {@code symptom}.
   */
  private static StackSnapshot snapshotOf(Symptom symptom) {
    final String envelope =
        "{\"version\":3,\"deployment\":{\"resources\":[{\"outputs\":{\"consultationReport\":{"
            + "\"checkpointId\":\"systemd-adapter\","
            + "\"dossiers\":[],"
            + "\"plan\":{\"symptom\":\""
            + symptom.id()
            + "\",\"generalistSummary\":\"s\",\"prescriptions\":[]}"
            + "}}}]}}";
    try {
      return StackSnapshot.of(StackDeployment.fromJson(envelope));
    } catch (Exception e) {
      throw new IllegalStateException("test envelope did not parse", e);
    }
  }

  /** A readable snapshot that raised no consultationReport — the healthy run. */
  private static StackSnapshot emptySnapshot() {
    try {
      return StackSnapshot.of(
          StackDeployment.fromJson("{\"version\":3,\"deployment\":{\"resources\":[]}}"));
    } catch (Exception e) {
      throw new IllegalStateException("test envelope did not parse", e);
    }
  }

  @Test
  void read_twoReadableEntries_returnsUsableRecord() throws Exception {
    final StackHistory.Entry v1 = entry(1);
    final StackHistory.Entry v2 = entry(2);
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
    final Complaint current = record.currentComplaint();
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
    final StackHistory.Entry v1 = entry(1);
    final SnapshotSource source = new FakeSnapshotSource().readable(v1, emptySnapshot());

    final MedicalRecord record = new MedicalRecordReader(source).read(PATIENT);

    // A healthy run is a visit that happened, not a failure and not a skipped entry.
    assertEquals(1, record.visits().size());
    assertTrue(record.visits().get(0).reports().isEmpty());
    assertTrue(record.currentComplaint().isEmpty());
  }

  @Test
  void read_unreadableTimeline_throwsWithEmptyPartialAndTheLeafSuppressed() {
    final StackAccessException leaf =
        new StackAccessException(Path.of("history"), new java.io.IOException("dir unreadable"));
    final SnapshotSource source = new FakeSnapshotSource().timelineUnreadable(leaf);

    final MedicalRecordReconstructionException ex =
        assertThrows(
            MedicalRecordReconstructionException.class,
            () -> new MedicalRecordReader(source).read(PATIENT));

    // No spine = no partial to build: an empty record, and the leaf itself suppressed (it already
    // carries path() — no entry identity to add).
    assertTrue(ex.partialRecord().visits().isEmpty());
    assertEquals(1, ex.getSuppressed().length);
    assertSame(leaf, ex.getSuppressed()[0]);
  }

  @Test
  void read_middleEntryFails_throwsWithPartialRecordAndIdentityEnrichedSuppressed() {
    final StackHistory.Entry v1 = entry(1);
    final StackHistory.Entry v2 = entry(2);
    final StackHistory.Entry v3 = entry(3);
    final StackContentException leaf =
        new StackContentException(v2.file(), new IllegalStateException("broken checkpoint"));
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

    // Suppressed: exactly one, identity-enriched (knows WHICH entry), cause is the leaf with path.
    assertEquals(1, ex.getSuppressed().length);
    final Throwable suppressed = ex.getSuppressed()[0];
    final MedicalRecordReconstructionException.EntryFailure failure =
        assertInstanceOf(MedicalRecordReconstructionException.EntryFailure.class, suppressed);
    assertEquals(2, failure.version());
    assertEquals(v2.when(), failure.when());
    assertSame(leaf, failure.getCause());
    assertEquals(v2.file(), ((StackContentException) failure.getCause()).path());
  }

  @Test
  void read_twoEntriesFail_suppressesBothAndKeepsTheRest() {
    final StackHistory.Entry v1 = entry(1);
    final StackHistory.Entry v2 = entry(2);
    final StackHistory.Entry v3 = entry(3);
    final SnapshotSource source =
        new FakeSnapshotSource()
            .failing(v1, new StackContentException(v1.file(), new IllegalStateException("a")))
            .readable(v2, snapshotOf(Symptom.TIMEOUT))
            .failing(v3, new StackContentException(v3.file(), new IllegalStateException("b")));

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
