package io.nxmatic.rke2lab.netplan.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.nxmatic.rke2lab.doctor.records.Observation;
import io.nxmatic.rke2lab.doctor.records.ReferralReply;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.doctor.testkit.TestReferrals;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The netplan diagnostician's contract: it always declines (assessing, never prescribing — no
 * automated network remediation yet) but always speaks the "why", so the runbook shows the recruit
 * seam. A plain JVM unit test (the diagnose is pure); the DS contribution is proven generically by
 * the doctor's HealthSystemContributionTest.
 */
class NetworkSpecialistTest {

  @Test
  void declines_connection_refused_with_a_why() {
    final Observation observation =
        Observation.failed(Symptom.CONNECTION_REFUSED, "refused", Map.of());
    final ReferralReply reply =
        new NetworkSpecialist().diagnose(TestReferrals.of(Symptom.CONNECTION_REFUSED, observation));

    assertFalse(reply.hasPrescription(), "the network specialist always declines");
    assertEquals("network/reachability/v1", reply.assessment().schemaRef().id());
    assertFalse(reply.assessment().summary().isBlank(), "the why is never blank");
  }
}
