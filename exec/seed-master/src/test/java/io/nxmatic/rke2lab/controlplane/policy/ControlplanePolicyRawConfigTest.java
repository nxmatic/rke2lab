package io.nxmatic.rke2lab.controlplane.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ControlplanePolicyRawConfigTest {

  @Test
  void readinessOverrideIsCarriedRaw() {
    final ControlplanePolicy.ReadinessPolicy readiness =
        new ControlplanePolicy.ReadinessPolicy(java.util.Map.of("systemd-adapter", "critical"));
    assertEquals(Optional.of("critical"), readiness.rawOverride("systemd-adapter"));
    assertTrue(readiness.rawOverride("absent").isEmpty());
  }

  @Test
  void previewSimulateIsCarriedRaw() {
    final ControlplanePolicy.PreviewPolicy preview =
        new ControlplanePolicy.PreviewPolicy(
            java.util.Map.of("systemd-adapter", "connection-refused"));
    assertEquals(Optional.of("connection-refused"), preview.rawSimulate("systemd-adapter"));
    assertTrue(preview.rawSimulate("absent").isEmpty());
  }

  @Test
  void rawOverrideSurfacesInOutputs() {
    final ControlplanePolicy.ReadinessPolicy readiness =
        new ControlplanePolicy.ReadinessPolicy(java.util.Map.of("systemd-adapter", "critical"));
    assertEquals("critical", readiness.toOutputMap().get("readiness.override.systemd-adapter"));
  }
}
