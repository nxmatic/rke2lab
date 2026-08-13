package io.seedmatic.rke2lab.cluster.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.seedmatic.rke2lab.doctor.contract.Assessment;
import io.seedmatic.rke2lab.doctor.contract.Observation;
import io.seedmatic.rke2lab.doctor.contract.Referral;
import io.seedmatic.rke2lab.doctor.contract.Symptom;
import io.seedmatic.rke2lab.doctor.testkit.TestReferrals;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The cluster diagnostician's contract: it voices cluster-layer concerns (kubeconfig, control-plane
 * readiness) but always declines (no automated cluster remediation yet), keeping the "why". A plain
 * JVM unit test (the two acts are pure); the DS contribution is proven generically by the doctor's
 * HealthSystemContributionTest.
 */
class ClusterSpecialistTest {

  @Test
  void voices_kubeconfig_missing_without_prescribing() {
    final Observation observation =
        Observation.failed(Symptom.KUBECONFIG_MISSING, "no kubeconfig", Map.of());
    final Referral referral = TestReferrals.of(Symptom.KUBECONFIG_MISSING, observation);
    final ClusterSpecialist specialist = new ClusterSpecialist();

    final Assessment assessment = specialist.assess(referral);
    assertEquals("cluster/kubeconfig/v1", assessment.schemaRef().id());
    assertFalse(assessment.summary().isBlank(), "the why is never blank");
    assertFalse(
        specialist.prescribe(referral, assessment).isPresent(),
        "the cluster specialist always declines");
  }
}
