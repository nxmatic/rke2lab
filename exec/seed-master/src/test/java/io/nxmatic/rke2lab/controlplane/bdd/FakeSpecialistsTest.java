package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.nxmatic.rke2lab.controlplane.config.OperatorConfiguration;
import io.nxmatic.rke2lab.doctor.ClinicalAccess;
import io.nxmatic.rke2lab.doctor.ClusterSpecialist;
import io.nxmatic.rke2lab.doctor.Generalist;
import io.nxmatic.rke2lab.doctor.GrantPolicy;
import io.nxmatic.rke2lab.doctor.MedicalRecord;
import io.nxmatic.rke2lab.doctor.MedicalRecordRegistry;
import io.nxmatic.rke2lab.doctor.NetworkSpecialist;
import io.nxmatic.rke2lab.doctor.Observation;
import io.nxmatic.rke2lab.doctor.Patient;
import io.nxmatic.rke2lab.doctor.Referral;
import io.nxmatic.rke2lab.doctor.ReferralReply;
import io.nxmatic.rke2lab.doctor.RemediationPlan;
import io.nxmatic.rke2lab.doctor.Specialist;
import io.nxmatic.rke2lab.doctor.Symptom;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Contracts for the two exemplar specialists that populate the consult fan-out. They always decline
 * (assessing, never prescribing) so the rendered runbook shows the recruit seam — a specialist's
 * voice explaining why it has no automated treatment yet.
 */
class FakeSpecialistsTest {

  private static final Patient PATIENT = new Patient("organization", "rke2lab", "test");

  private static Referral referral(Symptom symptom) {
    final Observation obs = Observation.failed(symptom, "x", Map.of());
    return Referral.of(PATIENT, symptom, obs, new MedicalRecord(PATIENT, List.of()));
  }

  @Test
  void network_declines_connection_refused_with_a_why() {
    final ReferralReply reply =
        new NetworkSpecialist().diagnose(referral(Symptom.CONNECTION_REFUSED));
    assertFalse(reply.hasPrescription(), "network specialist always declines");
    assertEquals("network/reachability/v1", reply.assessment().schemaRef().id());
    assertFalse(reply.assessment().summary().isBlank(), "the why is never blank");
  }

  @Test
  void cluster_voices_kubeconfig_missing() {
    final ReferralReply reply =
        new ClusterSpecialist().diagnose(referral(Symptom.KUBECONFIG_MISSING));
    assertFalse(reply.hasPrescription(), "cluster specialist always declines");
    assertEquals("cluster/kubeconfig/v1", reply.assessment().schemaRef().id());
  }

  @Test
  void network_and_dbus_both_reply_to_connection_refused() {
    // Mirror HealthSystemTest / GeneralistRecordRetrievalTest: build Generalist from
    // GrantPolicy + ClinicalAccess + specialists, using OperatorConfiguration.mandatory() for the
    // DbusTcpSpecialist's config.
    final GrantPolicy policy = GrantPolicy.empty().withSelfGrant(Generalist.GENERALIST_ID, PATIENT);
    final MedicalRecordRegistry registry = patient -> new MedicalRecord(patient, List.of());
    final ClinicalAccess access =
        new ClinicalAccess(Generalist.GENERALIST_ID, PATIENT, policy, registry, msg -> {});
    final List<Specialist> specialists =
        List.of(
            new DbusTcpSpecialist(OperatorConfiguration.mandatory().asBootstrapConfig()),
            new NetworkSpecialist());
    final Generalist generalist =
        Generalist.builder().specialists(specialists).access(access).build();

    final Observation observation =
        Observation.failed(Symptom.CONNECTION_REFUSED, "dbus refused", Map.of("source", "probe"));
    final RemediationPlan plan = generalist.consult(Symptom.CONNECTION_REFUSED, observation);

    assertEquals(2, plan.replies().size(), "both specialists route to CONNECTION_REFUSED");
    final long prescribed = plan.replies().stream().filter(ReferralReply::hasPrescription).count();
    assertEquals(1, prescribed, "exactly one specialist (dbus) prescribes; network declines");
  }
}
