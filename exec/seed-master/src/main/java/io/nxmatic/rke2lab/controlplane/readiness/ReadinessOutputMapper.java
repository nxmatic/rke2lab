package io.nxmatic.rke2lab.controlplane.readiness;

import com.pulumi.core.Output;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Functional mapper for readiness verification outputs.
 *
 * <p>Transforms readiness results into output map entries using a declarative pipeline approach,
 * eliminating repetitive output.put() calls.
 */
public final class ReadinessOutputMapper {

  private static final Map<
          String, Function<ClusterBootstrapReadinessVerifier.VerificationResult, Object>>
      OUTPUT_EXTRACTORS =
          Map.ofEntries(
              Map.entry(
                  "clusterReadinessEnabled",
                  ClusterBootstrapReadinessVerifier.VerificationResult::readinessEnabled),
              Map.entry("clusterReadinessSkipped", result -> !result.readinessEnabled()),
              Map.entry(
                  "clusterKubeconfigPublished",
                  ClusterBootstrapReadinessVerifier.VerificationResult::kubeconfigPublished),
              Map.entry(
                  "clusterApiReady",
                  ClusterBootstrapReadinessVerifier.VerificationResult::apiReady),
              Map.entry(
                  "clusterControllersEffective",
                  ClusterBootstrapReadinessVerifier.VerificationResult::controllersEffective),
              Map.entry(
                  "clusterRequiredControllers",
                  ClusterBootstrapReadinessVerifier.VerificationResult::requiredControllerRefs),
              Map.entry(
                  "clusterReadinessSummary",
                  ClusterBootstrapReadinessVerifier.VerificationResult::summary),
              Map.entry(
                  "handoffReady",
                  ClusterBootstrapReadinessVerifier.VerificationResult::handoffReady),
              Map.entry(
                  "bootstrapStatus",
                  ClusterBootstrapReadinessVerifier.VerificationResult::bootstrapStatus),
              Map.entry("nextStep", ReadinessOutputMapper::extractNextStep));

  private ReadinessOutputMapper() {}

  /**
   * Maps readiness output to output map entries.
   *
   * <p>Handles both Pulumi Output and direct VerificationResult values using functional
   * transformations.
   */
  public static Map<String, Object> mapToOutputs(Object readinessOutput) {
    if (readinessOutput instanceof Output<?> pulumiOutput) {
      return mapPulumiOutput(pulumiOutput);
    } else {
      return mapDirectValue((ClusterBootstrapReadinessVerifier.VerificationResult) readinessOutput);
    }
  }

  private static Map<String, Object> mapPulumiOutput(Output<?> readinessOutput) {
    @SuppressWarnings("unchecked")
    final Output<ClusterBootstrapReadinessVerifier.VerificationResult> typedOutput =
        (Output<ClusterBootstrapReadinessVerifier.VerificationResult>) readinessOutput;

    final Map<String, Object> outputs = new LinkedHashMap<>();
    OUTPUT_EXTRACTORS.forEach(
        (key, extractor) -> outputs.put(key, typedOutput.applyValue(extractor)));
    return outputs;
  }

  private static Map<String, Object> mapDirectValue(
      ClusterBootstrapReadinessVerifier.VerificationResult readiness) {
    final Map<String, Object> outputs = new LinkedHashMap<>(readiness.asOutputs());
    outputs.put("handoffReady", readiness.handoffReady());
    outputs.put("bootstrapStatus", readiness.bootstrapStatus());
    outputs.put("nextStep", extractNextStep(readiness));
    return outputs;
  }

  private static String extractNextStep(
      ClusterBootstrapReadinessVerifier.VerificationResult result) {
    return result.handoffReady()
        ? "bootstrap-management-cluster-then-apply-stageb-cluster-manifests"
        : "wait-for-cluster-readiness";
  }
}
