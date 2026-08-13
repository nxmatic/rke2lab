package io.seedmatic.rke2lab.netplan.internal;

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
 * The netplan diagnostician's contract: it always declines (assessing, never prescribing — no
 * automated network remediation yet) but always speaks the "why", so the runbook shows the recruit
 * seam. A plain JVM unit test (the two acts are pure); the DS contribution is proven generically by
 * the doctor's HealthSystemContributionTest.
 */
class NetworkSpecialistTest {

  @Test
  void declines_connection_refused_with_a_why() {
    final Observation observation =
        Observation.failed(Symptom.CONNECTION_REFUSED, "refused", Map.of());
    final Referral referral = TestReferrals.of(Symptom.CONNECTION_REFUSED, observation);
    final NetworkSpecialist specialist = new NetworkSpecialist();

    final Assessment assessment = specialist.assess(referral);
    assertEquals("network/reachability/v1", assessment.schemaRef().id());
    assertFalse(assessment.summary().isBlank(), "the why is never blank");
    assertFalse(
        specialist.prescribe(referral, assessment).isPresent(),
        "the network specialist always declines");
  }
}
