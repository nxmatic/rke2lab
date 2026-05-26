package io.nxmatic.rk2lab.controlplane.resources;

import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.Resource;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Component resource representing Stage A provisioning registry metadata. */
public final class BootstrapRegistryResource extends ComponentResource {

  private static final String TYPE_TOKEN = "rk2lab:controlplane:BootstrapRegistry";

  private final Map<String, Object> summary;

  public BootstrapRegistryResource(
      String name,
      BootstrapConfig config,
      Map<String, String> provisioningSliceChecksums,
      String hostSourceDirRelative,
      Map<String, Object> layerEnvRegistrySummary,
      Map<String, Object> systemdProvisioningSummary,
      Resource dependsOnResource) {
    super(TYPE_TOKEN, name, buildOptions(dependsOnResource));

    final LinkedHashMap<String, Object> values = new LinkedHashMap<>();
    values.put("sliceChecksums", provisioningSliceChecksums);
    values.put("hostSourceDirRelative", hostSourceDirRelative);
    values.put("localWorktreePath", config.localWorktreePath().toString());
    values.put("layerEnvRegistry", layerEnvRegistrySummary);
    values.put("systemdProvisioning", systemdProvisioningSummary);
    this.summary = Map.copyOf(values);

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
