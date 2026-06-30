package io.nxmatic.rke2lab.doctor.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nxmatic.rke2lab.doctor.records.*;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Domain;
import io.nxmatic.rke2lab.world.gateway.port.Patient;
import io.nxmatic.rke2lab.world.gateway.port.WorldGatewayCatalog;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the aggregator contract of {@link MedicalRecordReader} over the host {@code visit} {@link
 * Document}s: a per-entry fold that fails AT END (partial record + suppressed identity-enriched
 * failures), never fail-fast and never log-and-swallow. An empty journal is genuine nothing-here
 * (empty record, no exception). The CALLER decides what to do with the partial. The reader parses
 * each Document's blobs with its own jackson — the same shape {@code StackMedicalRecordJournal}
 * produces (version + when + the consultationReport/expectations blob lists).
 */
class MedicalRecordReaderTest {

  private static final Patient PATIENT = new Patient("organization", "rke2lab", "dev");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * A well-formed {@code visit} Document for {@code symptom} at {@code version}, like the journal.
   */
  private static Document visit(int version, Symptom symptom) {
    return visitOf(version, List.of(reportBlob(symptom)));
  }

  /** A readable visit that raised no consultationReport — the healthy run. */
  private static Document healthyVisit(int version) {
    return visitOf(version, List.of());
  }

  /** A malformed visit Document: a payload that is not parseable JSON. */
  private static Document brokenVisit(int version) {
    return new Document(Domain.DOCTOR.slug(), Coordinate.VISIT.slug(), "not json {");
  }

  private static Document visitOf(int version, List<Object> reportBlobs) {
    final LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
    payload.put(WorldGatewayCatalog.FIELD_VERSION, version);
    payload.put(WorldGatewayCatalog.FIELD_WHEN, Instant.ofEpochSecond(version).toString());
    payload.put(WorldGatewayCatalog.FIELD_CONSULTATION_REPORT, reportBlobs);
    payload.put(WorldGatewayCatalog.FIELD_EXPECTATIONS, List.of());
    return new Document(Domain.DOCTOR.slug(), Coordinate.VISIT.slug(), serialize(payload));
  }

  /**
   * One consultationReport blob (the {@code ConsultationReport.toOutputMap} shape) for {@code
   * symptom}.
   */
  private static Map<String, Object> reportBlob(Symptom symptom) {
    final Map<String, Object> plan = new LinkedHashMap<>();
    plan.put(Symptom.ENVELOPE_KEY, symptom.id());
    plan.put("generalistSummary", "s");
    plan.put("prescriptions", List.of());
    final Map<String, Object> report = new LinkedHashMap<>();
    report.put("checkpointId", "systemd-adapter");
    report.put("observations", List.of());
    report.put("plan", plan);
    return report;
  }

  private static String serialize(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void read_twoReadableEntries_returnsUsableRecord() throws Exception {
    final List<Document> journal =
        List.of(visit(1, Symptom.TIMEOUT), visit(2, Symptom.CONNECTION_REFUSED));

    final MedicalRecord record = new MedicalRecordReader().read(PATIENT, journal);

    assertEquals(PATIENT, record.patient());
    assertEquals(2, record.visits().size());
    assertEquals(1, record.visits().get(0).version());
    assertEquals(Instant.ofEpochSecond(1), record.visits().get(0).when());
    assertEquals(2, record.visits().get(1).version());
    assertEquals(Instant.ofEpochSecond(2), record.visits().get(1).when());

    // End-to-end: the fold produced a record the clinical views can actually answer.
    final ChiefComplaint current = record.chiefComplaint();
    assertEquals(1, current.reports().size());
    assertEquals(Symptom.CONNECTION_REFUSED, current.reports().get(0).symptom());
    assertEquals(1, record.historyOf(Symptom.TIMEOUT).occurrences().size());
    assertEquals(1, record.historyOf(Symptom.CONNECTION_REFUSED).occurrences().size());
  }

  @Test
  void read_emptyJournal_returnsEmptyRecordWithoutThrowing() throws Exception {
    final MedicalRecord record = new MedicalRecordReader().read(PATIENT, List.of());

    assertEquals(PATIENT, record.patient());
    assertTrue(record.visits().isEmpty());
  }

  @Test
  void read_readableEntryWithNoConsultationReport_yieldsVisitWithEmptyReports() throws Exception {
    final MedicalRecord record = new MedicalRecordReader().read(PATIENT, List.of(healthyVisit(1)));

    // A healthy run is a visit that happened, not a failure and not a skipped entry.
    assertEquals(1, record.visits().size());
    assertTrue(record.visits().get(0).reports().isEmpty());
    assertTrue(record.chiefComplaint().isEmpty());
  }

  @Test
  void read_middleEntryMalformed_throwsWithPartialRecordAndIdentityEnrichedSuppressed() {
    final List<Document> journal =
        List.of(visit(1, Symptom.TIMEOUT), brokenVisit(2), visit(3, Symptom.CONNECTION_REFUSED));

    final MedicalRecordReconstructionException ex =
        assertThrows(
            MedicalRecordReconstructionException.class,
            () -> new MedicalRecordReader().read(PATIENT, journal));

    // Partial: the two readable visits survive, the malformed one does not.
    final MedicalRecord partial = ex.partialRecord();
    assertEquals(2, partial.visits().size());
    assertEquals(List.of(1, 3), partial.visits().stream().map(Visit::version).toList());

    // Suppressed: exactly one, identity-enriched (knows the parse failed), the cause is reportable.
    assertEquals(1, ex.getSuppressed().length);
    final Throwable suppressed = ex.getSuppressed()[0];
    assertInstanceOf(MedicalRecordReconstructionException.EntryFailure.class, suppressed);
  }

  @Test
  void read_twoEntriesMalformed_suppressesBothAndKeepsTheRest() {
    final List<Document> journal =
        List.of(brokenVisit(1), visit(2, Symptom.TIMEOUT), brokenVisit(3));

    final MedicalRecordReconstructionException ex =
        assertThrows(
            MedicalRecordReconstructionException.class,
            () -> new MedicalRecordReader().read(PATIENT, journal));

    assertEquals(2, ex.getSuppressed().length);
    final MedicalRecord partial = ex.partialRecord();
    assertEquals(1, partial.visits().size());
    assertEquals(2, partial.visits().get(0).version());
    assertFalse(partial.visits().isEmpty());
  }
}
