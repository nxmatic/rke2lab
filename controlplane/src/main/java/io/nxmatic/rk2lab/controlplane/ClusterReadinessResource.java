package io.nxmatic.rk2lab.controlplane;

import com.pulumi.core.Output;
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

  private final ClusterBootstrapReadinessVerifier.VerificationResult verificationResult;

  public ClusterReadinessResource(
      String name,
      BootstrapConfig config,
      ControlplanePolicy policy,
      boolean readinessEnabled,
      Consumer<String> readinessLogger,
      Resource dependsOnResource) {
    super(TYPE_TOKEN, name, buildOptions(dependsOnResource));

    this.verificationResult =
        readinessEnabled
            ? ClusterBootstrapReadinessVerifier.verify(config, policy, readinessLogger)
            : ClusterBootstrapReadinessVerifier.skipped(policy, readinessLogger);

    registerOutputs(asResourceOutputs(verificationResult));
  }

  public ClusterBootstrapReadinessVerifier.VerificationResult verificationResult() {
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
      ClusterBootstrapReadinessVerifier.VerificationResult verificationResult) {
    final LinkedHashMap<String, Output<?>> outputs = new LinkedHashMap<>();

    verificationResult.asOutputs().forEach((key, value) -> outputs.put(key, Output.of(value)));

    outputs.put("handoffReady", Output.of(verificationResult.handoffReady()));
    outputs.put("bootstrapStatus", Output.of(verificationResult.bootstrapStatus()));

    return outputs;
  }
}
