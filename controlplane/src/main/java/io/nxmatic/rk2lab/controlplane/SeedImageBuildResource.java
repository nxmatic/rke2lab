package io.nxmatic.rk2lab.controlplane;

import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.Resource;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Component resource representing Stage A seed image build metadata. */
public final class SeedImageBuildResource extends ComponentResource {

  private static final String TYPE_TOKEN = "rk2lab:controlplane:SeedImageBuild";

  private final Map<String, Object> summary;

  public SeedImageBuildResource(
      String name,
      BootstrapConfig config,
      String imageBuildChecksum,
      Object imageFingerprint,
      Resource dependsOnResource) {
    super(TYPE_TOKEN, name, buildOptions(dependsOnResource));

    this.summary =
        Map.of(
            "checksum",
            imageBuildChecksum,
            "imageAlias",
            config.imageAlias(),
            "imageFingerprint",
            imageFingerprint,
            "incusProject",
            config.incusProject());

    registerOutputs(asResourceOutputs(summary));
  }

  public Map<String, Object> summary() {
    return summary;
  }

  private static ComponentResourceOptions buildOptions(Resource dependsOnResource) {
    final ComponentResourceOptions.Builder optionsBuilder = ComponentResourceOptions.builder();
    if (dependsOnResource != null) {
      optionsBuilder.dependsOn(List.of(dependsOnResource));
    }
    return optionsBuilder.build();
  }

  private static Map<String, Output<?>> asResourceOutputs(Map<String, Object> summary) {
    final LinkedHashMap<String, Output<?>> outputs = new LinkedHashMap<>();
    summary.forEach((key, value) -> outputs.put(key, Output.of(value)));
    return outputs;
  }
}
