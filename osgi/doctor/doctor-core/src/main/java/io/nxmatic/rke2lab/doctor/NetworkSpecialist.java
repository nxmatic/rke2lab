package io.nxmatic.rke2lab.doctor;

import java.util.Map;

/**
 * Network-domain exemplar specialist. Reads the observation and returns a reasoned {@link
 * Assessment} of network-layer concerns, but never prescribes — no automated network remediation
 * exists yet. This specialist gives the NETWORK domain a voice in the consult fan-out (the recruit
 * seam) until a real specialist that can treat network symptoms is added.
 */
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
