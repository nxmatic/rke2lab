package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import com.pulumi.Context;
import io.nxmatic.rke2lab.controlplane.SeedLog;
import java.util.Map;
import java.util.function.Supplier;

public final class OutputsTopic {

  private final Context pulumiContext;
  private final Supplier<Map<String, Object>> outputsSupplier;

  public OutputsTopic(Context pulumiContext, Supplier<Map<String, Object>> outputsSupplier) {
    this.pulumiContext = pulumiContext;
    this.outputsSupplier = outputsSupplier;
  }

  public OutputsTopic exportOrPrint() {
    final Map<String, Object> outputs = outputsSupplier.get();
    if (pulumiContext != null) {
      outputs.forEach(pulumiContext::export);
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
