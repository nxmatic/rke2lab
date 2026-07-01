package io.nxmatic.rke2lab.controlplane.systemd;

import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.Resource;
import io.nxmatic.rke2lab.world.gateway.codec.DocumentCodec;
import io.nxmatic.rke2lab.world.gateway.port.Consultation;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.WorldGatewayCatalog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Component resource representing systemd adapter launch/availability status in the Pulumi graph.
 */
public final class SystemdAdapterResource extends ComponentResource {

  private static final String TYPE_TOKEN = "rke2lab:controlplane:SystemdAdapter";

  private final Map<String, Object> summary;

  public SystemdAdapterResource(
      String name,
      Map<String, Object> summary,
      Optional<Document> consultation,
      Resource dependsOnResource) {
    super(TYPE_TOKEN, name, buildOptions(dependsOnResource));

    this.summary = Map.copyOf(summary == null ? Map.of() : summary);
    registerOutputs(asResourceOutputs(this.summary, consultation));
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

  private static Map<String, Output<?>> asResourceOutputs(
      Map<String, Object> summary, Optional<Document> consultation) {
    final LinkedHashMap<String, Output<?>> outputs = new LinkedHashMap<>();
    summary.forEach((key, value) -> outputs.put(key, Output.of(value)));
    // Additive, per-node only: the diagnostic layer lives under this component resource in state,
    // never at top level (the Stage-B stack contract stays byte-identical). The doctor reasons
    // OSGi-side; the host copies the structured sub-trees OPAQUELY from the consultation Document
    // to the same output keys (it holds no doctor type), and reconstruction reads them back by
    // name.
    consultation.ifPresent(document -> copyDiagnosticOutputs(document, outputs));
    return outputs;
  }

  /**
   * Copy the consultation's structured sub-trees to the egress keys, opaque to the host: the codec
   * decodes the Document into the seam {@link Consultation}, and the two blobs ({@code
   * consultationReport}, {@code expectations}) are carried through under the Pulumi output KEYS the
   * reconstruction reads them back by. Empty blobs are not emitted, so a symptomless consult leaves
   * the stack contract byte-identical.
   */
  private static void copyDiagnosticOutputs(
      Document consultation, LinkedHashMap<String, Output<?>> outputs) {
    final Consultation decoded = CODEC.decode(consultation, Consultation.class);
    if (!decoded.consultationReport().isEmpty()) {
      outputs.put(
          WorldGatewayCatalog.FIELD_CONSULTATION_REPORT, Output.of(decoded.consultationReport()));
    }
    if (!decoded.expectations().isEmpty()) {
      outputs.put(WorldGatewayCatalog.FIELD_EXPECTATIONS, Output.of(decoded.expectations()));
    }
  }

  private static final DocumentCodec CODEC = new DocumentCodec();
}
