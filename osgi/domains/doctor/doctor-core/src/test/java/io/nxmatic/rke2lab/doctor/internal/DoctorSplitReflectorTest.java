package io.nxmatic.rke2lab.doctor.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.records.Consultation;
import io.nxmatic.rke2lab.doctor.records.DoctorCoordinate;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.seed.broker.port.SplitCoordinate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The split verb doctor contributes: given a {@code consultation} seed, the reflector reads its
 * {@code @Scion}-marked components OSGi-side and hands them back grouped under the value of the
 * {@code @Rootstock} component, so the write frontier nests them holding no doctor class and no
 * storage-slot name. Pins the three behaviors the frontier relies on: populated scions come back
 * under their component names, grouped under the rootstock value (so the frontier can nest each
 * under its receiver); empty scions are omitted (a symptomless consult files nothing); the reaped
 * envelope carries the split meta-coordinate.
 */
class DoctorSplitReflectorTest {

  private static final SeedCodec CODEC = new SeedCodec();
  private static final DoctorSplitReflector REFLECTOR = new DoctorSplitReflector();

  @Test
  void serves_the_doctor_split_meta_coordinate() {
    assertEquals(new SplitCoordinate("doctor"), REFLECTOR.serves());
  }

  @Test
  void groups_populated_scions_under_the_rootstock_value() {
    final Consultation consultation =
        new Consultation(
            "systemd-adapter",
            "adapter unreachable",
            "= Diagnosis",
            Map.of("checkpointId", "systemd-adapter"),
            List.of(Map.of("symptom", "connection-refused")));

    final Map<String, Object> split = split(consultation);

    // One entry: the rootstock value (scenarioId), holding the scions by component name.
    assertEquals(
        Map.of(
            "systemd-adapter",
            Map.of(
                "consultationReport",
                Map.of("checkpointId", "systemd-adapter"),
                "expectations",
                List.of(Map.of("symptom", "connection-refused")))),
        split);
    // Never the flat fields the host reads directly (narration, diagnosisAdoc) nor scenarioId as a
    // scion — it is the grouping KEY, not a scion.
    assertFalse(split.containsKey("narration"));
    assertFalse(split.containsKey("diagnosisAdoc"));
  }

  @Test
  void omits_empty_scions_so_a_symptomless_consult_files_nothing() {
    final Consultation healthy =
        new Consultation("cluster-readiness", "all green", "", Map.of(), List.of());

    assertTrue(split(healthy).isEmpty());
  }

  @Test
  void reaps_at_the_split_meta_coordinate() {
    final SeedEnvelope reaped =
        REFLECTOR.handle(
            SeedEnvelope.of(
                DoctorCoordinate.CONSULTATION,
                CODEC.encode(new Consultation("s", "n", "", Map.of(), List.of()))));

    assertEquals("doctor", reaped.domain());
    assertEquals(SplitCoordinate.SLUG, reaped.coordinate());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> split(Consultation consultation) {
    final SeedEnvelope seed =
        SeedEnvelope.of(DoctorCoordinate.CONSULTATION, CODEC.encode(consultation));
    return CODEC.decode(REFLECTOR.handle(seed).payload(), Map.class);
  }
}
