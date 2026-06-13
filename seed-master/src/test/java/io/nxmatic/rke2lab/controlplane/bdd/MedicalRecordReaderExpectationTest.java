package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pulumi.automation.StackDeployment;
import io.nxmatic.rke2lab.pulumi.automation.StackAccessException;
import io.nxmatic.rke2lab.pulumi.automation.StackContentException;
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

    private final List<StackHistory.Entry> timeline = new ArrayList<>();
    private final Map<StackHistory.Entry, StackSnapshot> snapshots = new LinkedHashMap<>();

    FakeSnapshotSource readable(StackHistory.Entry entry, StackSnapshot snapshot) {
      timeline.add(entry);
      snapshots.put(entry, snapshot);
      return this;
    }

    @Override
    public List<StackHistory.Entry> timeline() {
      return List.copyOf(timeline);
    }

    @Override
    public StackSnapshot at(StackHistory.Entry entry)
        throws StackAccessException, StackContentException {
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
   * A snapshot whose single resource carries BOTH a {@code consultationReport} Map output AND an
   * {@code expectations} LIST output holding one expectation — exactly the shape B3 registers.
   */
  private static StackSnapshot snapshotWithExpectation(Symptom symptom, Expectation expectation) {
    final Map<String, Object> outputs = new LinkedHashMap<>();
    outputs.put(ConsultationReport.OUTPUT_KEY, consultationReportMap(symptom));
    outputs.put(Expectation.OUTPUT_KEY, List.of(expectation.toOutputMap()));
    return snapshotOf(outputs);
  }

  /** A healthy run: a readable resource carrying neither output. */
  private static StackSnapshot snapshotWithoutExpectation(Symptom symptom) {
    final Map<String, Object> outputs = new LinkedHashMap<>();
    outputs.put(ConsultationReport.OUTPUT_KEY, consultationReportMap(symptom));
    return snapshotOf(outputs);
  }

  /**
   * The diagnosed-but-referred case: the resource carries a {@code consultationReport} AND an
   * {@code expectations} key whose inner list is EMPTY — what B3 registers when a consultation
   * prescribed nothing ({@code Output.of(List.of())}).
   */
  private static StackSnapshot snapshotWithEmptyExpectations(Symptom symptom) {
    final Map<String, Object> outputs = new LinkedHashMap<>();
    outputs.put(ConsultationReport.OUTPUT_KEY, consultationReportMap(symptom));
    outputs.put(Expectation.OUTPUT_KEY, List.of());
    return snapshotOf(outputs);
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

  private static StackSnapshot snapshotOf(Map<String, Object> outputs) {
    final Map<String, Object> resource = new LinkedHashMap<>();
    resource.put("outputs", outputs);
    final Map<String, Object> deployment = new LinkedHashMap<>();
    deployment.put("resources", List.of(resource));
    final Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("version", 3);
    envelope.put("deployment", deployment);
    try {
      return StackSnapshot.of(StackDeployment.fromJson(toJson(envelope)));
    } catch (Exception e) {
      throw new IllegalStateException("test envelope did not parse", e);
    }
  }

  private static Expectation expectation(Symptom symptom, RemediationProgramRef program) {
    return new Expectation(
        symptom, program, new ResolutionPredicate(symptom), Instant.ofEpochSecond(1_780_000_000L));
  }

  @Test
  void read_entryWithExpectationsOutput_reconstructsThemIntoTheVisit() throws Exception {
    final RemediationProgramRef program = RemediationProgramRef.RESTART_UNIT;
    final Expectation expectation = expectation(Symptom.CONNECTION_REFUSED, program);
    final StackHistory.Entry v1 = entry(1);
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
    final StackHistory.Entry v1 = entry(1);
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
    final StackHistory.Entry v1 = entry(1);
    final SnapshotSource source =
        new FakeSnapshotSource().readable(v1, snapshotWithoutExpectation(Symptom.TIMEOUT));

    final MedicalRecord record = new MedicalRecordReader(source).read(PATIENT);

    final Visit visit = record.visits().get(0);
    assertTrue(visit.expectations().isEmpty());
    assertEquals(1, visit.reports().size());
    assertEquals(Symptom.TIMEOUT, visit.reports().get(0).symptom());
  }

  private static String toJson(Object value) {
    if (value instanceof Map<?, ?> map) {
      final StringBuilder sb = new StringBuilder("{");
      boolean first = true;
      for (Map.Entry<?, ?> e : map.entrySet()) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        sb.append('"').append(e.getKey()).append("\":").append(toJson(e.getValue()));
      }
      return sb.append('}').toString();
    }
    if (value instanceof List<?> list) {
      final StringBuilder sb = new StringBuilder("[");
      boolean first = true;
      for (Object e : list) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        sb.append(toJson(e));
      }
      return sb.append(']').toString();
    }
    if (value instanceof String s) {
      return '"' + s + '"';
    }
    return String.valueOf(value);
  }
}
