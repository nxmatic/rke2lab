package io.nxmatic.rke2lab.netplan.internal;

import io.nxmatic.rke2lab.doctor.records.Assessment;
import io.nxmatic.rke2lab.doctor.records.Referral;
import io.nxmatic.rke2lab.doctor.records.ReferralReply;
import io.nxmatic.rke2lab.doctor.records.SchemaRef;
import io.nxmatic.rke2lab.doctor.records.Specialty;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.doctor.spi.ClinicianProperties;
import io.nxmatic.rke2lab.doctor.spi.Specialist;
import java.util.Map;
import org.osgi.service.component.annotations.Component;

/**
 * The netplan domain's diagnostician: it reads a network-layer observation and returns a reasoned
 * {@link Assessment}, but never prescribes — no automated network remediation exists yet. It gives
 * the NETWORK domain a voice in the consult fan-out until a treating specialist is added.
 *
 * <p>Contributed to the doctor by Declarative Services: a {@link Specialist} {@code @Component}
 * self-declaring the domain-level diagnostician properties ({@link
 * ClinicianProperties#PROP_DIAGNOSTICIAN} + {@link ClinicianProperties#PROP_TIER_DOMAIN}), which
 * the health system's tier-scoped {@code @Reference} collects. It lives in the netplan domain (its
 * owner), not in doctor-core — the domain defines its own specialist. The class is in a
 * non-exported package: it crosses to the doctor as the {@code Specialist} service, never as a
 * shared type.
 */
@Component(
    service = Specialist.class,
    property = {ClinicianProperties.PROP_DIAGNOSTICIAN, ClinicianProperties.PROP_TIER_DOMAIN})
public final class NetworkSpecialist implements Specialist {

  @Override
  public Specialty domain() {
    return Specialty.NETWORK;
  }

  @Override
  public ReferralReply diagnose(Referral referral) {
    final Symptom symptom = referral.symptom();
    final Assessment assessment =
        switch (symptom) {
          case CONNECTION_REFUSED, TIMEOUT ->
              Assessment.of(
                  SchemaRef.of("network/reachability/v1"),
                  Map.of("symptom", symptom.id()),
                  "endpoint unreachable at the TCP layer; no network-level remediation — the listener is down, not the path");
          case API_NOT_READY ->
              Assessment.of(
                  SchemaRef.of("network/api-path/v1"),
                  Map.of("symptom", symptom.id()),
                  "API endpoint not reachable yet; network path is the suspect, but no automated fix — investigate routing");
          default ->
              Assessment.of(
                  SchemaRef.of("network/other/v1"),
                  Map.of("symptom", symptom.id()),
                  "not a network symptom — no network assessment for " + symptom.id());
        };
    return ReferralReply.assessing(referral, assessment);
  }
}
