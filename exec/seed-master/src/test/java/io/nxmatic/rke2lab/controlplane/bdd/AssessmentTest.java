package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.Assessment;
import io.nxmatic.rke2lab.doctor.SchemaRef;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AssessmentTest {

  @Test
  void to_output_map_is_flat_and_string_keyed() {
    final Assessment assessment =
        Assessment.of(
            SchemaRef.of("dbus-tcp/declined/v1"),
            Map.of("reason", "not my domain"),
            "TCP refused; nothing to treat network-side");

    final Map<String, Object> map = assessment.toOutputMap();

    assertEquals("dbus-tcp/declined/v1", map.get("schemaRef"));
    assertEquals("TCP refused; nothing to treat network-side", map.get("summary"));
    final Map<?, ?> payloadMap = (Map<?, ?>) map.get("payload");
    assertEquals("not my domain", payloadMap.get("reason"));
  }

  @Test
  void null_summary_is_rejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Assessment.of(SchemaRef.of("x/y/v1"), Map.of(), null));

    assertThrows(
        IllegalArgumentException.class,
        () -> Assessment.of(SchemaRef.of("x/y/v1"), Map.of(), "  "));
  }

  @Test
  void null_payload_defaults_empty() {
    final Assessment assessment = Assessment.of(SchemaRef.of("x/y/v1"), null, "why");
    assertTrue(assessment.payload().isEmpty());
  }
}
