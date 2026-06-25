package io.nxmatic.rke2lab.doctor.dsproof;

import io.nxmatic.rke2lab.doctor.records.Assessment;
import io.nxmatic.rke2lab.doctor.records.Prescription;
import io.nxmatic.rke2lab.doctor.records.Referral;
import io.nxmatic.rke2lab.doctor.records.ReferralReply;
import io.nxmatic.rke2lab.doctor.records.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.records.SchemaRef;
import io.nxmatic.rke2lab.doctor.records.Specialty;
import io.nxmatic.rke2lab.doctor.spi.ClinicianProperties;
import io.nxmatic.rke2lab.doctor.spi.Specialist;
import java.util.Map;
import org.osgi.service.component.annotations.Component;

/**
 * The DS boot proof's diagnostician: a {@link Specialist} {@code @Component} self-declaring the
 * domain-level diagnostician properties ({@link ClinicianProperties#PROP_DIAGNOSTICIAN} + {@link
 * ClinicianProperties#PROP_TIER_DOMAIN}) — exactly what {@code DefaultHealthSystem}'s tier-scoped
 * {@code @Reference} target selects. If SCR binds this into the institution's roster and a consult
 * routes to it, the whole DS-contribution path holds end to end.
 *
 * <p>Routes on {@link Specialty#SYSTEMD} (the domain {@code CONNECTION_REFUSED} routes to) and
 * always prescribes, so a consult on that symptom yields a non-empty plan we can assert on.
 */
@Component(
    service = Specialist.class,
    property = {ClinicianProperties.PROP_DIAGNOSTICIAN, ClinicianProperties.PROP_TIER_DOMAIN})
public final class FakeDiagnostician implements Specialist {

  public static final SchemaRef SCHEMA_REF = SchemaRef.of("test/ds-proof-diagnostician/v1");

  @Override
  public Specialty domain() {
    return Specialty.SYSTEMD;
  }

  @Override
  public ReferralReply diagnose(Referral referral) {
    final Assessment assessment =
        Assessment.of(
            SCHEMA_REF,
            Map.of("symptom", referral.symptom().id()),
            "ds-proof diagnostician reasoning for " + referral.symptom().id());
    final Prescription prescription =
        Prescription.of(
            RemediationProgramRef.RESTART_UNIT,
            Map.of("symptom", referral.symptom().id()),
            "ds-proof prescription");
    return ReferralReply.prescribing(referral, assessment, prescription);
  }
}
