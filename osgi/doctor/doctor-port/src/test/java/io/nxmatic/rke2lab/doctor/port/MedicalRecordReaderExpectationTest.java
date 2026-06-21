package io.nxmatic.rke2lab.doctor.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the read-back of persisted {@link Expectation}s into the {@link Visit} timeline (Task B4).
 * The B3 producer registers {@code expectations} as a LIST per resource ({@code
 * Output.of(List<Map>)}), so {@code outputsNamed("expectations")} returns a list-of-lists — one
 * inner list per resource that has the key — whereas {@code consultationReport} is a single Map per
 * resource. This test fixes that exact shape difference and confirms the reader flattens the extra
 * level while leaving the reports fold unchanged.
 */
class MedicalRecordReaderExpectationTest {

  private static final Patient PATIENT = new Patient("organization", "rke2lab", "dev");

  private static final class FakeSnapshotSource implements SnapshotSource {

    private final List<SnapshotEntry> timeline = new ArrayList<>();
    private final Map<SnapshotEntry, SnapshotView> snapshots = new LinkedHashMap<>();

    FakeSnapshotSource readable(SnapshotEntry entry, SnapshotView snapshot) {
      timeline.add(entry);
      snapshots.put(entry, snapshot);
      return this;
    }

    @Override
    public List<SnapshotEntry> timeline() {
      return List.copyOf(timeline);
    }

    @Override
    public SnapshotView at(SnapshotEntry entry) {
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

  /**
   * A snapshot whose single resource carries BOTH a {@code consultationReport} Map output AND an
   * {@code expectations} LIST output holding one expectation — exactly the shape B3 registers. In
   * SnapshotView terms: consultationReport is a singleton per-resource list of the report map;
   * expectations is a singleton per-resource list whose one element is the inner list of maps.
   */
  private static SnapshotView snapshotWithExpectation(Symptom symptom, Expectation expectation) {
    return new SnapshotView(
        Map.of(
            ConsultationReport.OUTPUT_KEY,
            List.of(consultationReportMap(symptom)),
            Expectation.OUTPUT_KEY,
            List.of(List.of(expectation.toOutputMap()))));
  }

  /** A healthy run: a readable resource carrying only the consultationReport. */
  private static SnapshotView snapshotWithoutExpectation(Symptom symptom) {
    return new SnapshotView(
        Map.of(ConsultationReport.OUTPUT_KEY, List.of(consultationReportMap(symptom))));
  }

  /**
   * The diagnosed-but-referred case: the resource carries a {@code consultationReport} AND an
   * {@code expectations} key whose inner list is EMPTY — what B3 registers when a consultation
   * prescribed nothing ({@code Output.of(List.of())}).
   */
  private static SnapshotView snapshotWithEmptyExpectations(Symptom symptom) {
    return new SnapshotView(
        Map.of(
            ConsultationReport.OUTPUT_KEY,
            List.of(consultationReportMap(symptom)),
            Expectation.OUTPUT_KEY,
            List.of(List.of())));
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
    final SnapshotEntry v1 = entry(1);
    final SnapshotSource source =
        new FakeSnapshotSource()
            .readable(v1, snapshotWithExpectation(Symptom.CONNECTION_REFUSED, expectation));

    final MedicalRecord record = new MedicalRecordReader(source).read(PATIENT);

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
    final SnapshotEntry v1 = entry(1);
    final SnapshotSource source =
        new FakeSnapshotSource().readable(v1, snapshotWithEmptyExpectations(Symptom.TIMEOUT));

    final MedicalRecord record = new MedicalRecordReader(source).read(PATIENT);

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
    final SnapshotEntry v1 = entry(1);
    final SnapshotSource source =
        new FakeSnapshotSource().readable(v1, snapshotWithoutExpectation(Symptom.TIMEOUT));

    final MedicalRecord record = new MedicalRecordReader(source).read(PATIENT);

    final Visit visit = record.visits().get(0);
    assertTrue(visit.expectations().isEmpty());
    assertEquals(1, visit.reports().size());
    assertEquals(Symptom.TIMEOUT, visit.reports().get(0).symptom());
  }
}
