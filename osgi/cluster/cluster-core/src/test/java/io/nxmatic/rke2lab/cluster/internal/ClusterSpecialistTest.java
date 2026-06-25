package io.nxmatic.rke2lab.cluster.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.nxmatic.rke2lab.doctor.records.Observation;
import io.nxmatic.rke2lab.doctor.records.ReferralReply;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.doctor.testkit.TestReferrals;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The cluster diagnostician's contract: it voices cluster-layer concerns (kubeconfig, control-plane
 * readiness) but always declines (no automated cluster remediation yet), keeping the "why". A plain
 * JVM unit test (the diagnose is pure); the DS contribution is proven generically by the doctor's
 * HealthSystemContributionTest.
 */
class ClusterSpecialistTest {

  @Test
  void voices_kubeconfig_missing_without_prescribing() {
    final Observation observation =
        Observation.failed(Symptom.KUBECONFIG_MISSING, "no kubeconfig", Map.of());
    final ReferralReply reply =
        new ClusterSpecialist().diagnose(TestReferrals.of(Symptom.KUBECONFIG_MISSING, observation));

    assertFalse(reply.hasPrescription(), "the cluster specialist always declines");
    assertEquals("cluster/kubeconfig/v1", reply.assessment().schemaRef().id());
    assertFalse(reply.assessment().summary().isBlank(), "the why is never blank");
  }
}
