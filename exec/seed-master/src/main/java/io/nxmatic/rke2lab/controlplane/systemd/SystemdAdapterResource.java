package io.nxmatic.rke2lab.controlplane.systemd;

import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.Resource;
import io.nxmatic.rke2lab.doctor.records.ConsultationReport;
import io.nxmatic.rke2lab.doctor.records.Expectation;
import java.time.Instant;
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
      Optional<ConsultationReport> consultation,
      Instant recordedAt,
      Resource dependsOnResource) {
    super(TYPE_TOKEN, name, buildOptions(dependsOnResource));

    this.summary = Map.copyOf(summary == null ? Map.of() : summary);
    registerOutputs(asResourceOutputs(this.summary, consultation, recordedAt));
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
      Map<String, Object> summary, Optional<ConsultationReport> consultation, Instant recordedAt) {
    final LinkedHashMap<String, Output<?>> outputs = new LinkedHashMap<>();
    summary.forEach((key, value) -> outputs.put(key, Output.of(value)));
    // Additive, per-node only: the diagnostic layer lives under this component resource in state,
    // never at top level (the Stage-B stack contract stays byte-identical). A prescribing
    // consultation also writes what it predicts, so the next visit can detect whether the
    // intervention resolved the symptom.
    consultation.ifPresent(
        report -> {
          outputs.put(ConsultationReport.OUTPUT_KEY, Output.of(report.toOutputMap()));
          outputs.put(
              Expectation.OUTPUT_KEY,
              Output.of(
                  report.expectations(recordedAt).stream().map(Expectation::toOutputMap).toList()));
        });
    return outputs;
  }
}
