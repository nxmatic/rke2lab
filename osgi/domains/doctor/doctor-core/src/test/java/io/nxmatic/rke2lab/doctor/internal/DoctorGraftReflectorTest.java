package io.nxmatic.rke2lab.doctor.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.records.Consultation;
import io.nxmatic.rke2lab.doctor.records.DoctorCoordinate;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.GraftCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The introspection verb doctor contributes: given a {@code consultation} seed, the reflector reads
 * its {@code @Graft}-marked components OSGi-side and hands them back BY NAME, so the write frontier
 * files them holding no doctor class and no storage-slot name. Pins the two behaviors the frontier
 * relies on: populated grafts come back under their component names (the byte-identical stack
 * contract), and empty ones are omitted (a symptomless consult files nothing).
 */
class DoctorGraftReflectorTest {

  private static final SeedCodec CODEC = new SeedCodec();
  private static final DoctorGraftReflector REFLECTOR = new DoctorGraftReflector();

  @Test
  void serves_the_doctor_graft_meta_coordinate() {
    assertEquals(new GraftCoordinate("doctor"), REFLECTOR.serves());
  }

  @Test
  void hands_back_populated_grafts_by_component_name() {
    final Consultation consultation =
        new Consultation(
            "systemd-adapter",
            "adapter unreachable",
            "= Diagnosis",
            Map.of("checkpointId", "systemd-adapter"),
            List.of(Map.of("symptom", "connection-refused")));

    final Map<String, Object> grafts = reflect(consultation);

    assertEquals(Map.of("checkpointId", "systemd-adapter"), grafts.get("consultationReport"));
    assertEquals(List.of(Map.of("symptom", "connection-refused")), grafts.get("expectations"));
    // Only the grafts — never the flat fields the host reads directly (scenarioId, narration…).
    assertFalse(grafts.containsKey("scenarioId"));
    assertFalse(grafts.containsKey("narration"));
    assertFalse(grafts.containsKey("diagnosisAdoc"));
  }

  @Test
  void omits_empty_grafts_so_a_symptomless_consult_files_nothing() {
    final Consultation healthy =
        new Consultation("cluster-readiness", "all green", "", Map.of(), List.of());

    assertTrue(reflect(healthy).isEmpty());
  }

  @Test
  void reaps_at_the_graft_meta_coordinate() {
    final SeedEnvelope reaped =
        REFLECTOR.handle(
            SeedEnvelope.of(
                DoctorCoordinate.CONSULTATION,
                CODEC.encode(new Consultation("s", "n", "", Map.of(), List.of()))));

    assertEquals("doctor", reaped.domain());
    assertEquals(GraftCoordinate.SLUG, reaped.coordinate());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> reflect(Consultation consultation) {
    final SeedEnvelope seed =
        SeedEnvelope.of(DoctorCoordinate.CONSULTATION, CODEC.encode(consultation));
    return CODEC.decode(REFLECTOR.handle(seed).payload(), Map.class);
  }
}
