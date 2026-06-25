package io.nxmatic.rke2lab.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.nxmatic.rke2lab.doctor.internal.*;
import io.nxmatic.rke2lab.doctor.port.MedicalRecordRegistry;
import io.nxmatic.rke2lab.doctor.records.Assessment;
import io.nxmatic.rke2lab.doctor.records.MedicalRecord;
import io.nxmatic.rke2lab.doctor.records.Observation;
import io.nxmatic.rke2lab.doctor.records.Patient;
import io.nxmatic.rke2lab.doctor.records.Referral;
import io.nxmatic.rke2lab.doctor.records.ReferralReply;
import io.nxmatic.rke2lab.doctor.records.RemediationPlan;
import io.nxmatic.rke2lab.doctor.records.SchemaRef;
import io.nxmatic.rke2lab.doctor.records.Specialty;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.doctor.spi.Specialist;
import io.nxmatic.rke2lab.doctor.testkit.FakeSpecialist;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The Generalist's fan-out contract: when a symptom routes to several specialties, every routed
 * specialist replies and the plan carries each reply, with prescriptions counted across them. The
 * per-domain specialists' OWN behaviour (a network or cluster decline with its schema) is proven in
 * their own modules now; here we assert only the Generalist's collection, on host-independent fakes
 * — a prescribing {@link FakeSpecialist} (SYSTEMD) and a declining double (NETWORK).
 */
class GeneralistFanOutTest {

  private static final Patient PATIENT = new Patient("organization", "rke2lab", "test");

  /** A specialist that always declines on its domain — assesses, never prescribes. */
  private record DecliningSpecialist(Specialty domain) implements Specialist {
    @Override
    public ReferralReply diagnose(Referral referral) {
      final Assessment assessment =
          Assessment.of(
              SchemaRef.of("test/declining/v1"),
              Map.of("symptom", referral.symptom().id()),
              "declined: no automated treatment");
      return ReferralReply.assessing(referral, assessment);
    }
  }

  @Test
  void a_prescribing_and_a_declining_specialist_both_reply_to_connection_refused() {
    // CONNECTION_REFUSED routes to SYSTEMD + NETWORK. A prescribing fake (SYSTEMD) and a declining
    // fake (NETWORK) both reply; exactly one prescribes. Asserted on the reply count, not on any
    // host specialist's schema.
    final GrantPolicy policy = GrantPolicy.empty().withSelfGrant(Generalist.GENERALIST_ID, PATIENT);
    final MedicalRecordRegistry registry = patient -> new MedicalRecord(patient, List.of());
    final ClinicalAccess access =
        new ClinicalAccess(Generalist.GENERALIST_ID, PATIENT, policy, registry, msg -> {});
    final List<Specialist> specialists =
        List.of(new FakeSpecialist(), new DecliningSpecialist(Specialty.NETWORK));
    final Generalist generalist =
        Generalist.builder().specialists(specialists).access(access).build();

    final Observation observation =
        Observation.failed(Symptom.CONNECTION_REFUSED, "refused", Map.of("source", "probe"));
    final RemediationPlan plan = generalist.consult(Symptom.CONNECTION_REFUSED, observation);

    assertEquals(2, plan.replies().size(), "both specialists route to CONNECTION_REFUSED");
    final long prescribed = plan.replies().stream().filter(ReferralReply::hasPrescription).count();
    assertEquals(1, prescribed, "exactly one specialist prescribes; the network double declines");
  }
}
