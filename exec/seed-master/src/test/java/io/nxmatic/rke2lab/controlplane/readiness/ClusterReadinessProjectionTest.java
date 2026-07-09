package io.nxmatic.rke2lab.controlplane.readiness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.controlplane.config.OperatorConfiguration;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.readiness.ClusterBootstrapReadinessVerifier.VerificationResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Increment D HARD constraint: when the cluster-readiness checkpoint becomes a BDD scenario, the
 * VerificationResult output contract must be byte-identical to before. The stage builds the result
 * via the verifier's public projection factories; this pins the keys the output layer + Stage B
 * handoff (handoffReady → nextStep) depend on.
 */
class ClusterReadinessProjectionTest {

  @Test
  void ready_projection_signals_handoff_to_stage_b() {
    final VerificationResult result =
        ClusterBootstrapReadinessVerifier.ready(RequiredControllers.from(policy()));
    assertEquals("Ready", result.bootstrapStatus());
    assertTrue(result.handoffReady());

    final Map<String, Object> outputs = ReadinessOutputMapper.mapToOutputs(result);
    assertEquals(true, outputs.get("clusterApiReady"));
    assertEquals(true, outputs.get("clusterControllersEffective"));
    assertEquals(
        "bootstrap-management-cluster-then-apply-stageb-cluster-manifests",
        outputs.get("nextStep"));
  }

  @Test
  void failed_projection_holds_handoff_and_carries_phase_flags() {
    // api-ready phase failed: kubeconfig ok, api false, controllers not reached.
    final VerificationResult result =
        ClusterBootstrapReadinessVerifier.failed(
            true,
            false,
            false,
            "kubernetes API did not report readyz=ok",
            RequiredControllers.from(policy()));
    assertEquals("Failed", result.bootstrapStatus());
    assertFalse(result.handoffReady());

    final Map<String, Object> outputs = ReadinessOutputMapper.mapToOutputs(result);
    assertEquals(true, outputs.get("clusterKubeconfigPublished"));
    assertEquals(false, outputs.get("clusterApiReady"));
    assertEquals("wait-for-cluster-readiness", outputs.get("nextStep"));
  }

  private static ControlplanePolicy policy() {
    return OperatorConfiguration.mandatory().asPolicy();
  }
}
