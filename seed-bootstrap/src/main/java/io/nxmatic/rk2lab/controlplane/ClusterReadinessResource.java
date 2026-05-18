package io.nxmatic.rk2lab.controlplane;

import com.pulumi.core.Output;
import com.pulumi.deployment.Deployment;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.Resource;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rk2lab.controlplane.policy.ControlplanePolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Component resource representing bootstrap readiness verification in the Pulumi graph. */
public final class ClusterReadinessResource extends ComponentResource {

  private static final String TYPE_TOKEN = "rk2lab:controlplane:ClusterReadiness";

  private final Output<ClusterBootstrapReadinessVerifier.VerificationResult> verificationResult;

  public ClusterReadinessResource(
      String name,
      BootstrapConfig config,
      ControlplanePolicy policy,
      boolean readinessEnabled,
      Consumer<String> readinessLogger,
      Object readinessTrigger,
      Resource dependsOnResource) {
    super(TYPE_TOKEN, name, buildOptions(dependsOnResource));

    this.verificationResult =
        Output.of(readinessTrigger)
            .applyValue(
                ignored ->
                    Deployment.getInstance().isDryRun()
                        ? ClusterBootstrapReadinessVerifier.deferredPreview(policy, readinessLogger)
                        : readinessEnabled
                            ? ClusterBootstrapReadinessVerifier.verify(
                                config, policy, readinessLogger)
                            : ClusterBootstrapReadinessVerifier.skipped(policy, readinessLogger));

    registerOutputs(asResourceOutputs(verificationResult));
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
      Output<ClusterBootstrapReadinessVerifier.VerificationResult> verificationResult) {
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

    return outputs;
  }
}
