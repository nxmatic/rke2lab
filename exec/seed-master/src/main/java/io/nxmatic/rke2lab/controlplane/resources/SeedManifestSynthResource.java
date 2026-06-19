package io.nxmatic.rke2lab.controlplane.resources;

import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Component resource exposing the cdk8s synth + explode result as a Pulumi-tracked artifact.
 *
 * <p>The synth itself runs inside {@code IncusResourceBootstrap.prepareHostState()} at apply time;
 * this resource carries the checksum and per-layer breakdown produced there. {@code pulumi preview}
 * therefore shows a diff whenever the {@link
 * io.nxmatic.rke2lab.manifests.bridge.profiles.FloxDebugPolicy} flips or any source resource (layer
 * code, classpath asset, manifest unit) changes shape — without each individual exploded YAML
 * becoming a Pulumi resource.
 */
public final class SeedManifestSynthResource extends ComponentResource {

  private static final String TYPE_TOKEN = "rke2lab:controlplane:SeedManifestSynth";

  private final Map<String, Object> summary;

  public SeedManifestSynthResource(
      String name, Map<String, Object> manifestSynthSummary, Resource dependsOnResource) {
    super(TYPE_TOKEN, name, buildOptions(dependsOnResource));

    this.summary = manifestSynthSummary == null ? Map.of() : Map.copyOf(manifestSynthSummary);

    registerOutputs(asResourceOutputs(this.summary));
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
