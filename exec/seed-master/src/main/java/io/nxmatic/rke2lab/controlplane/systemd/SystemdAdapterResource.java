package io.nxmatic.rke2lab.controlplane.systemd;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.Resource;
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
   * Copy the consultation Document's structured sub-trees to the egress keys, opaque to the host.
   */
  private static void copyDiagnosticOutputs(
      Document consultation, LinkedHashMap<String, Output<?>> outputs) {
    final JsonNode payload = parse(consultation.payload());
    final JsonNode report = payload.path(WorldGatewayCatalog.FIELD_CONSULTATION_REPORT);
    if (report.isObject()) {
      outputs.put(WorldGatewayCatalog.FIELD_CONSULTATION_REPORT, Output.of(asPlainObject(report)));
    }
    final JsonNode expectations = payload.path(WorldGatewayCatalog.FIELD_EXPECTATIONS);
    if (expectations.isArray()) {
      outputs.put(WorldGatewayCatalog.FIELD_EXPECTATIONS, Output.of(asPlainList(expectations)));
    }
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode parse(String payload) {
    try {
      return MAPPER.readTree(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("malformed consultation payload", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asPlainObject(JsonNode node) {
    return MAPPER.convertValue(node, Map.class);
  }

  @SuppressWarnings("unchecked")
  private static List<Object> asPlainList(JsonNode node) {
    return MAPPER.convertValue(node, List.class);
  }
}
