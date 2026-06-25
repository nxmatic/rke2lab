package io.nxmatic.rke2lab.doctor;

import io.nxmatic.rke2lab.doctor.records.*;
import io.nxmatic.rke2lab.doctor.records.Assessment;
import io.nxmatic.rke2lab.doctor.records.Referral;
import io.nxmatic.rke2lab.doctor.records.ReferralReply;
import io.nxmatic.rke2lab.doctor.records.SchemaRef;
import io.nxmatic.rke2lab.doctor.records.Specialty;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.doctor.spi.Specialist;
import java.util.Map;

/**
 * Cluster-domain exemplar specialist. Reads the observation and returns a reasoned {@link
 * Assessment} of cluster-layer concerns (kubeconfig, control-plane readiness), but never prescribes
 * — no automated cluster remediation exists yet. This specialist gives the CLUSTER domain a voice
 * in the consult fan-out (the recruit seam) until a real specialist that can treat cluster symptoms
 * is added.
 */
final class ClusterSpecialist implements Specialist {

  @Override
  public Specialty domain() {
    return Specialty.CLUSTER;
  }

  @Override
  public ReferralReply diagnose(Referral referral) {
    final Symptom symptom = referral.symptom();
    final Assessment assessment =
        switch (symptom) {
          case KUBECONFIG_MISSING ->
              Assessment.of(
                  SchemaRef.of("cluster/kubeconfig/v1"),
                  Map.of("symptom", symptom.id()),
                  "kubeconfig not yet written by the control-plane bootstrap; expected during early readiness — no treatment, awaiting convergence");
          case CONTROLLER_NOT_READY ->
              Assessment.of(
                  SchemaRef.of("cluster/controller/v1"),
                  Map.of("symptom", symptom.id()),
                  "control-plane controller not Ready; a cluster specialist would inspect the kubelet/static pods — not yet automated");
          case API_NOT_READY ->
              Assessment.of(
                  SchemaRef.of("cluster/api/v1"),
                  Map.of("symptom", symptom.id()),
                  "kube-apiserver not serving yet; awaiting control-plane readiness");
          default ->
              Assessment.of(
                  SchemaRef.of("cluster/other/v1"),
                  Map.of("symptom", symptom.id()),
                  "not a cluster symptom — no cluster assessment for " + symptom.id());
        };
    return ReferralReply.assessing(referral, assessment);
  }
}
