package io.nxmatic.rke2lab.doctor.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.records.Expectation;
import io.nxmatic.rke2lab.doctor.records.MedicalRecord;
import io.nxmatic.rke2lab.doctor.records.ProblemRef;
import io.nxmatic.rke2lab.doctor.records.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.records.ResolutionPredicate;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.doctor.records.Visit;
import io.nxmatic.rke2lab.seed.broker.codec.DocumentCodec;
import io.nxmatic.rke2lab.seed.broker.port.Checkpoint;
import io.nxmatic.rke2lab.seed.broker.port.Coordinate;
import io.nxmatic.rke2lab.seed.broker.port.Document;
import io.nxmatic.rke2lab.seed.broker.port.Domain;
import io.nxmatic.rke2lab.seed.broker.port.Patient;
import io.nxmatic.rke2lab.seed.broker.port.VisitWire;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the read-back of persisted {@link Expectation}s into the {@link Visit} timeline. The
 * producer registers {@code expectations} as a LIST per resource ({@code Output.of(List<Map>)}), so
 * the host journal harvests a list-of-lists — one inner list per resource that has the key —
 * whereas {@code consultationReport} is a single Map per resource. This test fixes that exact shape
 * in the {@code visit} Document and confirms the reader flattens the extra level while leaving the
 * reports fold unchanged.
 */
class MedicalRecordReaderExpectationTest {

  private static final Patient PATIENT = new Patient("organization", "rke2lab", "dev");
  private static final DocumentCodec CODEC = new DocumentCodec();

  /**
   * A {@code visit} Document whose single resource carries BOTH a {@code consultationReport} Map
   * AND an {@code expectations} LIST holding one expectation — exactly the shape the producer
   * registers and the journal harvests: consultationReport is a singleton per-resource list of the
   * report map; expectations is a singleton per-resource list whose one element is the inner list
   * of maps.
   */
  private static Document visitWithExpectation(Symptom symptom, Expectation expectation) {
    return visitDocument(
        List.of(consultationReportMap(symptom)), List.of(List.of(CODEC.toMap(expectation))));
  }

  /** A healthy run: a resource carrying only the consultationReport. */
  private static Document visitWithoutExpectation(Symptom symptom) {
    return visitDocument(List.of(consultationReportMap(symptom)), List.of());
  }

  /**
   * The diagnosed-but-referred case: a {@code consultationReport} AND an {@code expectations} key
   * whose inner list is EMPTY — what the producer registers when a consultation prescribed nothing.
   */
  private static Document visitWithEmptyExpectations(Symptom symptom) {
    return visitDocument(List.of(consultationReportMap(symptom)), List.of(List.of()));
  }

  private static Document visitDocument(List<Object> reportBlobs, List<Object> expectationBlobs) {
    final VisitWire visit =
        new VisitWire(1, Instant.ofEpochSecond(1), reportBlobs, expectationBlobs);
    return new Document(Domain.DOCTOR.slug(), Coordinate.VISIT.slug(), CODEC.encode(visit));
  }

  private static Map<String, Object> consultationReportMap(Symptom symptom) {
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

  private static Expectation expectation(Symptom symptom, RemediationProgramRef program) {
    return new Expectation(
        ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, symptom),
        program,
        new ResolutionPredicate(symptom),
        Instant.ofEpochSecond(1_780_000_000L));
  }

  @Test
  void read_entryWithExpectationsOutput_reconstructsThemIntoTheVisit() throws Exception {
    final RemediationProgramRef program = RemediationProgramRef.RESTART_UNIT;
    final Expectation expectation = expectation(Symptom.CONNECTION_REFUSED, program);
    final List<Document> journal =
        List.of(visitWithExpectation(Symptom.CONNECTION_REFUSED, expectation));

    final MedicalRecord record = new MedicalRecordReader().read(PATIENT, journal);

    final Visit visit = record.visits().get(0);
    // Expectations round-trip the LIST-per-resource shape into the visit.
    assertEquals(1, visit.expectations().size());
    final Expectation reconstructed = visit.expectations().get(0);
    assertEquals(Symptom.CONNECTION_REFUSED, reconstructed.symptom());
    assertEquals(program, reconstructed.fromPrescription());
    // Reports fold is untouched.
    assertEquals(1, visit.reports().size());
    assertEquals(Symptom.CONNECTION_REFUSED, visit.reports().get(0).symptom());
  }

  @Test
  void read_entryWithEmptyExpectationsList_yieldsEmptyExpectationsButKeepsReports()
      throws Exception {
    final List<Document> journal = List.of(visitWithEmptyExpectations(Symptom.TIMEOUT));

    final MedicalRecord record = new MedicalRecordReader().read(PATIENT, journal);

    final Visit visit = record.visits().get(0);
    // The present-but-empty inner list flattens to nothing.
    assertTrue(visit.expectations().isEmpty());
    // Reports fold is untouched.
    assertEquals(1, visit.reports().size());
    assertEquals(Symptom.TIMEOUT, visit.reports().get(0).symptom());
  }

  @Test
  void read_entryWithoutExpectationsOutput_yieldsEmptyExpectationsButKeepsReports()
      throws Exception {
    final List<Document> journal = List.of(visitWithoutExpectation(Symptom.TIMEOUT));

    final MedicalRecord record = new MedicalRecordReader().read(PATIENT, journal);

    final Visit visit = record.visits().get(0);
    assertTrue(visit.expectations().isEmpty());
    assertEquals(1, visit.reports().size());
    assertEquals(Symptom.TIMEOUT, visit.reports().get(0).symptom());
  }
}
