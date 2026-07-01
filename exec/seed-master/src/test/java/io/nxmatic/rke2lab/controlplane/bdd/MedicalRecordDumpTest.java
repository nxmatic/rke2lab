package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.nxmatic.rke2lab.doctor.port.MedicalRecordJournal;
import io.nxmatic.rke2lab.world.gateway.codec.DocumentCodec;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Domain;
import io.nxmatic.rke2lab.world.gateway.port.Patient;
import io.nxmatic.rke2lab.world.gateway.port.VisitWire;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the two host-pure halves of {@link MedicalRecordDump}: the deterministic YAML serialization
 * ({@code toYaml}) and the lenient-but-informed error policy ({@code dump}). The dump reads its OWN
 * timeline through the host {@link MedicalRecordJournal} (opaque {@code visit} Documents) and
 * transcodes each visit's stored {@code consultationReport} blob JSON→YAML — no OSGi call, no
 * {@code doctor.records} type. These tests pass a fake journal; the live wiring needs a real
 * backend and is exercised in the sandbox.
 */
class MedicalRecordDumpTest {

  private static final Patient PATIENT = new Patient("organization", "rke2lab", "dev");
  private static final DocumentCodec CODEC = new DocumentCodec();

  /**
   * A fake journal: an ordered list of {@code visit} Documents, as the host journal would yield.
   */
  private static MedicalRecordJournal journalOf(List<Document> visits) {
    return new MedicalRecordJournal() {
      @Override
      public List<Document> historyOf(Patient patient) {
        return List.copyOf(visits);
      }

      @Override
      public List<Patient> cohort(Patient current) {
        return List.of(current);
      }
    };
  }

  /**
   * A well-formed {@code visit} Document at {@code version} carrying one consultationReport blob.
   */
  private static Document visit(int version, String checkpointId) {
    final Map<String, Object> report = new LinkedHashMap<>();
    report.put("checkpointId", checkpointId);
    report.put("observations", List.of());
    report.put("plan", Map.of("symptom", "connection-refused", "generalistSummary", "s"));
    final VisitWire visit =
        new VisitWire(version, Instant.ofEpochSecond(version), List.of(report), List.of());
    return new Document(Domain.DOCTOR.slug(), Coordinate.VISIT.slug(), CODEC.encode(visit));
  }

  /** A malformed visit Document: an unparseable JSON payload. */
  private static Document brokenVisit() {
    return new Document(Domain.DOCTOR.slug(), Coordinate.VISIT.slug(), "not json {");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseYaml(String yaml) {
    try {
      return new ObjectMapper(new YAMLFactory()).readValue(yaml, Map.class);
    } catch (Exception e) {
      throw new IllegalStateException("emitted YAML did not parse", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> asListOfMaps(Object value) {
    return (List<Map<String, Object>>) value;
  }

  @Test
  void toYaml_roundTripsTheLoadBearingFieldsAndIsParseable() {
    final List<Map<String, Object>> visits = new ArrayList<>();
    final Map<String, Object> v1 = new LinkedHashMap<>();
    v1.put("version", 1);
    v1.put("when", Instant.ofEpochSecond(1000).toString());
    v1.put("reports", List.of(Map.of("checkpointId", "systemd-adapter")));
    visits.add(v1);

    final String yaml = MedicalRecordDump.toYaml(PATIENT, visits);

    assertTrue(yaml.contains(PATIENT.qualifiedName()), () -> yaml);
    assertTrue(yaml.contains("systemd-adapter"), () -> yaml);

    final Map<String, Object> parsed = parseYaml(yaml);
    assertEquals(PATIENT.qualifiedName(), parsed.get("patient"));
    final List<Map<String, Object>> back = asListOfMaps(parsed.get("visits"));
    assertEquals(1, back.size());
    assertEquals(1, back.get(0).get("version"));
    assertEquals(Instant.ofEpochSecond(1000).toString(), back.get(0).get("when"));
  }

  @Test
  void toYaml_emptyTimeline_isValidYamlWithEmptyVisits() {
    final String yaml = MedicalRecordDump.toYaml(PATIENT, List.of());

    final Map<String, Object> parsed = parseYaml(yaml);
    assertEquals(PATIENT.qualifiedName(), parsed.get("patient"));
    assertEquals(List.of(), parsed.get("visits"));
  }

  @Test
  void dump_success_emitsFullRecordWithZeroExitAndNoFailures() {
    final MedicalRecordJournal journal =
        journalOf(List.of(visit(1, "systemd-adapter"), visit(2, "cluster-readiness")));

    final MedicalRecordDump.Result result = MedicalRecordDump.dump(PATIENT, journal);

    assertEquals(0, result.exitCode());
    assertTrue(result.failures().isEmpty());
    final Map<String, Object> parsed = parseYaml(result.yaml());
    assertEquals(2, ((List<?>) parsed.get("visits")).size());
  }

  @Test
  void dump_oneEntryMalformed_dumpsThePartialAndSurfacesTheFailureWithNonZeroExit() {
    final MedicalRecordJournal journal =
        journalOf(
            List.of(visit(1, "systemd-adapter"), brokenVisit(), visit(3, "cluster-readiness")));

    final MedicalRecordDump.Result result = MedicalRecordDump.dump(PATIENT, journal);

    // The caller decided: a partial record is still emitted (the two readable visits).
    assertNotEquals(0, result.exitCode());
    final Map<String, Object> parsed = parseYaml(result.yaml());
    final List<Map<String, Object>> visits = asListOfMaps(parsed.get("visits"));
    assertEquals(2, visits.size());
    assertEquals(List.of(1, 3), visits.stream().map(v -> v.get("version")).toList());

    // The failure is SURFACED, not swallowed.
    assertFalse(result.failures().isEmpty());
  }
}
