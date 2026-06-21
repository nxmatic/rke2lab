package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.nxmatic.rke2lab.doctor.port.ConsultationReport;
import io.nxmatic.rke2lab.doctor.port.MedicalRecord;
import io.nxmatic.rke2lab.doctor.port.Observation;
import io.nxmatic.rke2lab.doctor.port.Patient;
import io.nxmatic.rke2lab.doctor.port.Prescription;
import io.nxmatic.rke2lab.doctor.port.RemediationPlan;
import io.nxmatic.rke2lab.doctor.port.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.port.SnapshotContentException;
import io.nxmatic.rke2lab.doctor.port.SnapshotEntry;
import io.nxmatic.rke2lab.doctor.port.SnapshotSource;
import io.nxmatic.rke2lab.doctor.port.SnapshotView;
import io.nxmatic.rke2lab.doctor.port.Symptom;
import io.nxmatic.rke2lab.doctor.port.Visit;
import io.nxmatic.rke2lab.doctor.testkit.ReferralReplies;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the two unit-testable halves of {@link MedicalRecordDump}: the deterministic YAML
 * serialization ({@code toYaml}) and the lenient-but-informed error policy ({@code dump}). The live
 * {@code attach(...)} wiring in {@code main} needs a real backend and is exercised in the sandbox,
 * not here — these tests pass a fake {@link SnapshotSource}.
 */
class MedicalRecordDumpTest {

  private static final Patient PATIENT = new Patient("organization", "rke2lab", "dev");

  /**
   * Same in-test seam shape as {@code MedicalRecordReaderTest}: per entry, a snapshot or a throw.
   */
  private static final class FakeSnapshotSource implements SnapshotSource {

    private final List<SnapshotEntry> timeline = new ArrayList<>();
    private final Map<SnapshotEntry, SnapshotView> snapshots = new LinkedHashMap<>();
    private final Map<SnapshotEntry, SnapshotContentException> contentFailures =
        new LinkedHashMap<>();

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

    @Override
    public List<SnapshotEntry> timeline() {
      return List.copyOf(timeline);
    }

    @Override
    public SnapshotView at(SnapshotEntry entry) throws SnapshotContentException {
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

  private static String location(int version) {
    return "v" + version + ".json";
  }

  private static SnapshotView snapshotOf(Symptom symptom) {
    final Map<String, Object> plan = new LinkedHashMap<>();
    plan.put(Symptom.ENVELOPE_KEY, symptom.id());
    plan.put("generalistSummary", "s");
    plan.put("replies", List.of());
    final Map<String, Object> report = new LinkedHashMap<>();
    report.put("checkpointId", "systemd-adapter");
    report.put("observations", List.of());
    report.put("plan", plan);
    return new SnapshotView(Map.of(ConsultationReport.OUTPUT_KEY, List.of(report)));
  }

  /** A synthetic record with two visits; the first carries a symptom + a prescription. */
  private static MedicalRecord sampleRecord() {
    final ConsultationReport report =
        new ConsultationReport(
            "systemd-adapter",
            List.of(
                Observation.failed(
                    Symptom.CONNECTION_REFUSED,
                    "dbus port refused",
                    Map.of("source", "systemd-adapter"))),
            new RemediationPlan(
                Symptom.CONNECTION_REFUSED,
                List.of(
                    ReferralReplies.treating(
                        Prescription.of(
                            RemediationProgramRef.CHECK_CONNECTIVITY,
                            Map.of("port", 12434),
                            "verify the dbus-over-tcp listener is up"))),
                "the adapter could not reach dbus"));
    final Visit v1 = new Visit(1, Instant.ofEpochSecond(1000), List.of(report), List.of());
    final Visit v2 = new Visit(2, Instant.ofEpochSecond(2000), List.of(), List.of());
    return new MedicalRecord(PATIENT, List.of(v1, v2));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseYaml(String yaml) {
    try {
      return new ObjectMapper(new YAMLFactory()).readValue(yaml, Map.class);
    } catch (Exception e) {
      throw new IllegalStateException("emitted YAML did not parse", e);
    }
  }

  @Test
  void toYaml_roundTripsTheLoadBearingFieldsAndIsParseable() {
    final String yaml = MedicalRecordDump.toYaml(sampleRecord());

    // The load-bearing identifiers survive into the text.
    assertTrue(yaml.contains(PATIENT.qualifiedName()), () -> yaml);
    assertTrue(yaml.contains(Symptom.CONNECTION_REFUSED.id()), () -> yaml);
    assertTrue(yaml.contains(RemediationProgramRef.CHECK_CONNECTIVITY.id()), () -> yaml);

    // It is real, parseable YAML with the documented top-level shape.
    final Map<String, Object> parsed = parseYaml(yaml);
    assertEquals(PATIENT.qualifiedName(), parsed.get("patient"));
    final List<Map<String, Object>> visits = asListOfMaps(parsed.get("visits"));
    assertEquals(2, visits.size());
    assertEquals(1, visits.get(0).get("version"));
    assertEquals(2, visits.get(1).get("version"));
    assertEquals(Instant.ofEpochSecond(1000).toString(), visits.get(0).get("when"));
  }

  @Test
  void toYaml_emptyRecord_isValidYamlWithEmptyVisits() {
    final String yaml = MedicalRecordDump.toYaml(new MedicalRecord(PATIENT, List.of()));

    final Map<String, Object> parsed = parseYaml(yaml);
    assertEquals(PATIENT.qualifiedName(), parsed.get("patient"));
    assertEquals(List.of(), parsed.get("visits"));
  }

  @Test
  void dump_success_emitsFullRecordWithZeroExitAndNoFailures() {
    final SnapshotSource source =
        new FakeSnapshotSource()
            .readable(entry(1), snapshotOf(Symptom.TIMEOUT))
            .readable(entry(2), snapshotOf(Symptom.CONNECTION_REFUSED));

    final MedicalRecordDump.Result result = MedicalRecordDump.dump(PATIENT, source);

    assertEquals(0, result.exitCode());
    assertTrue(result.failures().isEmpty());
    final Map<String, Object> parsed = parseYaml(result.yaml());
    assertEquals(2, ((List<?>) parsed.get("visits")).size());
  }

  @Test
  void dump_oneEntryUnreadable_dumpsThePartialAndSurfacesTheFailureWithNonZeroExit() {
    final SnapshotEntry v2 = entry(2);
    final SnapshotContentException leaf =
        new SnapshotContentException(location(2), new IllegalStateException("broken checkpoint"));
    final SnapshotSource source =
        new FakeSnapshotSource()
            .readable(entry(1), snapshotOf(Symptom.TIMEOUT))
            .failing(v2, leaf)
            .readable(entry(3), snapshotOf(Symptom.CONNECTION_REFUSED));

    final MedicalRecordDump.Result result = MedicalRecordDump.dump(PATIENT, source);

    // The caller decided: a partial record is still emitted (the two readable visits).
    assertNotEquals(0, result.exitCode());
    final Map<String, Object> parsed = parseYaml(result.yaml());
    final List<Map<String, Object>> visits = asListOfMaps(parsed.get("visits"));
    assertEquals(2, visits.size());
    assertEquals(List.of(1, 3), visits.stream().map(v -> v.get("version")).toList());

    // The failure is SURFACED, not swallowed: identity (version) + the leaf's location are
    // reportable.
    assertFalse(result.failures().isEmpty());
    final String report = String.join("\n", result.failures());
    assertTrue(report.contains("2"), () -> report);
    assertTrue(report.contains(location(2)), () -> report);
  }

  /**
   * Parsed YAML visits are a list of {@code Map<String, Object>} by the dump's documented shape.
   */
  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> asListOfMaps(Object value) {
    return (List<Map<String, Object>>) value;
  }
}
