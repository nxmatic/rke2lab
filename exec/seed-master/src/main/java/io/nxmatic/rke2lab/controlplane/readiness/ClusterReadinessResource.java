package io.nxmatic.rke2lab.controlplane.readiness;

import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.Resource;
import io.nxmatic.rke2lab.doctor.records.ConsultationReport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thin Pulumi-graph mirror of the cluster-readiness result. The checkpoint is played eagerly as a
 * BDD scenario by {@code ClusterReadinessStage} (which records it into the runbook and consults the
 * doctor); this resource only registers the already-computed {@link
 * ClusterBootstrapReadinessVerifier.VerificationResult} as graph outputs and carries the {@code
 * dependsOn} edge — the same shape as {@code SystemdAdapterResource}. No verification runs here.
 */
public final class ClusterReadinessResource extends ComponentResource {

  private static final String TYPE_TOKEN = "rke2lab:controlplane:ClusterReadiness";

  private final Output<ClusterBootstrapReadinessVerifier.VerificationResult> verificationResult;

  public ClusterReadinessResource(
      String name,
      ClusterBootstrapReadinessVerifier.VerificationResult result,
      Optional<ConsultationReport> consultation,
      Resource dependsOnResource) {
    super(TYPE_TOKEN, name, buildOptions(dependsOnResource));

    this.verificationResult = Output.of(result);
    registerOutputs(asResourceOutputs(verificationResult, consultation));
  }

  public Output<ClusterBootstrapReadinessVerifier.VerificationResult> verificationResult() {
    return verificationResult;
  }

  private static ComponentResourceOptions buildOptions(Resource dependsOnResource) {
    final ComponentResourceOptions.Builder optionsBuilder = ComponentResourceOptions.builder();
    if (dependsOnResource != null) {
      optionsBuilder.dependsOn(List.of(dependsOnResource));
    }
    return optionsBuilder.build();
  }

  private static Map<String, Output<?>> asResourceOutputs(
      Output<ClusterBootstrapReadinessVerifier.VerificationResult> verificationResult,
      Optional<ConsultationReport> consultation) {
    final LinkedHashMap<String, Output<?>> outputs = new LinkedHashMap<>();

    outputs.put(
        "clusterReadinessEnabled",
        verificationResult.applyValue(value -> value.readinessEnabled()));
    outputs.put(
        "clusterReadinessSkipped",
        verificationResult.applyValue(value -> !value.readinessEnabled()));
    outputs.put(
        "clusterKubeconfigPublished",
        verificationResult.applyValue(value -> value.kubeconfigPublished()));
    outputs.put("clusterApiReady", verificationResult.applyValue(value -> value.apiReady()));
    outputs.put(
        "clusterControllersEffective",
        verificationResult.applyValue(value -> value.controllersEffective()));
    outputs.put(
        "clusterRequiredControllers",
        verificationResult.applyValue(value -> value.requiredControllerRefs()));
    outputs.put("clusterReadinessSummary", verificationResult.applyValue(value -> value.summary()));
    outputs.put("handoffReady", verificationResult.applyValue(value -> value.handoffReady()));
    outputs.put("bootstrapStatus", verificationResult.applyValue(value -> value.bootstrapStatus()));

    // Additive, per-node only: the diagnostic layer lives under this component resource in state,
    // never at top level (the Stage-B stack contract stays byte-identical).
    consultation.ifPresent(
        report -> outputs.put(ConsultationReport.OUTPUT_KEY, Output.of(report.toOutputMap())));

    return outputs;
  }
}
