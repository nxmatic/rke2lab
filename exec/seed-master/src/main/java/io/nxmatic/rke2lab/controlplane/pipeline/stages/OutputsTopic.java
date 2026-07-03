package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import com.pulumi.Context;
import io.nxmatic.rke2lab.controlplane.SeedLog;
import io.nxmatic.rke2lab.pipeline.Topic;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Outputs topic — exports (Pulumi) or prints (standalone) the collected stack outputs. Its input is
 * read via a {@link Supplier} — the read-face dual of a sink — because it is the terminal consumer
 * of the cluster-seed output.
 */
public final class OutputsTopic implements Topic.Execution {

  private final Optional<Context> pulumiContext;
  private final Supplier<Map<String, Object>> outputsSupplier;

  public OutputsTopic(
      Optional<Context> pulumiContext, Supplier<Map<String, Object>> outputsSupplier) {
    this.pulumiContext = pulumiContext;
    this.outputsSupplier = outputsSupplier;
  }

  @Override
  public String role() {
    return "outputs";
  }

  public OutputsTopic exportOrPrint() {
    final Map<String, Object> outputs = outputsSupplier.get();
    if (pulumiContext.isPresent()) {
      final Context context = pulumiContext.get();
      outputs.forEach(context::export);
    } else {
      SeedLog.info(
          "standalone",
          "Pulumi engine not detected (missing PULUMI_MONITOR). Running in standalone mode.");
      SeedLog.info("standalone", "Bootstrap outputs:");
      outputs.forEach((key, value) -> SeedLog.info("standalone", key + "=" + value));
    }
    return this;
  }
}
