package io.seedmatic.rke2lab.cluster.internal;

import io.seedmatic.rke2lab.cluster.contract.ClusterSchemaRef;
import io.seedmatic.rke2lab.doctor.contract.Assessment;
import io.seedmatic.rke2lab.doctor.contract.Prescription;
import io.seedmatic.rke2lab.doctor.contract.Referral;
import io.seedmatic.rke2lab.doctor.contract.SchemaRef;
import io.seedmatic.rke2lab.doctor.contract.Specialty;
import io.seedmatic.rke2lab.doctor.contract.Symptom;
import io.seedmatic.rke2lab.doctor.spi.ClinicianProperties;
import io.seedmatic.rke2lab.doctor.spi.Specialist;
import java.util.Map;
import java.util.Optional;
import org.osgi.service.component.annotations.Component;

/**
 * The cluster domain's diagnostician: it reads a cluster-layer observation (kubeconfig,
 * control-plane readiness) and returns a reasoned {@link Assessment}, but never prescribes — no
 * automated cluster remediation exists yet. It gives the CLUSTER domain a voice in the consult
 * fan-out until a treating specialist is added.
 *
 * <p>Contributed to the doctor by Declarative Services: a {@link Specialist} {@code @Component}
 * self-declaring the domain-level diagnostician properties ({@link
 * ClinicianProperties#PROP_DIAGNOSTICIAN} + {@link ClinicianProperties#PROP_TIER_DOMAIN}), which
 * the health system's tier-scoped {@code @Reference} collects. It lives in the cluster domain (its
 * owner), not in doctor-core — the domain defines its own specialist. The class is in a
 * non-exported package: it crosses to the doctor as the {@code Specialist} service, never as a
 * shared type.
 */
@Component(
    service = Specialist.class,
    property = {ClinicianProperties.PROP_DIAGNOSTICIAN, ClinicianProperties.PROP_TIER_DOMAIN})
public final class ClusterSpecialist implements Specialist {

  @Override
  public Specialty domain() {
    return Specialty.CLUSTER;
  }

  @Override
  public Assessment assess(Referral referral) {
    final Symptom symptom = referral.symptom();
    return switch (symptom) {
      case KUBECONFIG_MISSING ->
          Assessment.of(
              SchemaRef.of(ClusterSchemaRef.KUBECONFIG.id()),
              Map.of("symptom", symptom.id()),
              "kubeconfig not yet written by the control-plane bootstrap; expected during early readiness — no treatment, awaiting convergence");
      case CONTROLLER_NOT_READY ->
          Assessment.of(
              SchemaRef.of(ClusterSchemaRef.CONTROLLER.id()),
              Map.of("symptom", symptom.id()),
              "control-plane controller not Ready; a cluster specialist would inspect the kubelet/static pods — not yet automated");
      case API_NOT_READY ->
          Assessment.of(
              SchemaRef.of(ClusterSchemaRef.API.id()),
              Map.of("symptom", symptom.id()),
              "kube-apiserver not serving yet; awaiting control-plane readiness");
      default ->
          Assessment.of(
              SchemaRef.of(ClusterSchemaRef.OTHER.id()),
              Map.of("symptom", symptom.id()),
              "not a cluster symptom — no cluster assessment for " + symptom.id());
    };
  }

  /** Never prescribes — no automated cluster remediation exists yet (assessment-only). */
  @Override
  public Optional<Prescription> prescribe(Referral referral, Assessment assessment) {
    return Optional.empty();
  }
}
