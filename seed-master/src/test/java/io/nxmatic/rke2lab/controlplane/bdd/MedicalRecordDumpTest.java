package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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

    private final List<StackHistory.Entry> timeline = new ArrayList<>();
    private final Map<StackHistory.Entry, StackSnapshot> snapshots = new LinkedHashMap<>();
    private final Map<StackHistory.Entry, StackContentException> contentFailures =
        new LinkedHashMap<>();

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

    @Override
    public List<StackHistory.Entry> timeline() {
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

  private static StackSnapshot snapshotOf(Symptom symptom) {
    final String envelope =
        "{\"version\":3,\"deployment\":{\"resources\":[{\"outputs\":{\"consultationReport\":{"
            + "\"checkpointId\":\"systemd-adapter\","
            + "\"observations\":[],"
            + "\"plan\":{\"symptom\":\""
            + symptom.id()
            + "\",\"generalistSummary\":\"s\",\"replies\":[]}"
            + "}}}]}}";
    try {
      return StackSnapshot.of(StackDeployment.fromJson(envelope));
    } catch (Exception e) {
      throw new IllegalStateException("test envelope did not parse", e);
    }
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
    final Visit v1 = new Visit(1, Instant.ofEpochSecond(1000), List.of(report));
    final Visit v2 = new Visit(2, Instant.ofEpochSecond(2000), List.of());
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
    final List<Map<String, Object>> visits = (List<Map<String, Object>>) parsed.get("visits");
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
  void dump_success_emitsFullRecordWithZeroExitAndNoFailures() throws StackException {
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
  void dump_oneEntryUnreadable_dumpsThePartialAndSurfacesTheFailureWithNonZeroExit()
      throws StackException {
    final StackHistory.Entry v2 = entry(2);
    final StackContentException leaf =
        new StackContentException(v2.file(), new IllegalStateException("broken checkpoint"));
    final SnapshotSource source =
        new FakeSnapshotSource()
            .readable(entry(1), snapshotOf(Symptom.TIMEOUT))
            .failing(v2, leaf)
            .readable(entry(3), snapshotOf(Symptom.CONNECTION_REFUSED));

    final MedicalRecordDump.Result result = MedicalRecordDump.dump(PATIENT, source);

    // The caller decided: a partial record is still emitted (the two readable visits).
    assertNotEquals(0, result.exitCode());
    final Map<String, Object> parsed = parseYaml(result.yaml());
    final List<Map<String, Object>> visits = (List<Map<String, Object>>) parsed.get("visits");
    assertEquals(2, visits.size());
    assertEquals(List.of(1, 3), visits.stream().map(v -> v.get("version")).toList());

    // The failure is SURFACED, not swallowed: identity (version) + the leaf's path are reportable.
    assertFalse(result.failures().isEmpty());
    final String report = String.join("\n", result.failures());
    assertTrue(report.contains("2"), () -> report);
    assertTrue(report.contains(v2.file().toString()), () -> report);
  }
}
