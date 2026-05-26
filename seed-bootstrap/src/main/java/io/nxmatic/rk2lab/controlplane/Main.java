package io.nxmatic.rk2lab.controlplane;

import com.pulumi.Config;
import com.pulumi.Pulumi;
import io.nxmatic.rk2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rk2lab.controlplane.config.ConfigResolver;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rk2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rk2lab.controlplane.resources.ResourceManager;
import java.util.Map;
import java.util.function.Consumer;

/** Entry point for the Pulumi management-cluster bootstrap program. */
public final class Main {

  private final BboxReconciliationOrchestrator bboxOrchestrator;
  private final ResourceManager resourceManager;
  private final OutputBuilder outputBuilder;

  private Main(boolean pulumiMode) {
    this.bboxOrchestrator = new BboxReconciliationOrchestrator(pulumiMode);
    this.resourceManager = new ResourceManager();
    this.outputBuilder = new OutputBuilder();
  }

  public static void main(String[] args) {
    final boolean pulumiMode = isPulumiEngineAvailable();
    final Main instance = new Main(pulumiMode);

    if (!pulumiMode) {
      instance.runStandalone();
      return;
    }

    Pulumi.run(
        context -> {
          SeedLog.installPulumiLogSink(
              (event, message) -> {
                switch (event) {
                  case ERROR -> context.log().error(message);
                  case WARN -> context.log().warn(message);
                  case INFO -> context.log().info(message);
                  case DEBUG, TRACE -> context.log().debug(message);
                }
              });
          try {
            final Config config = context.config("rke2lab");
            final BootstrapConfig bootstrapConfig =
                new BootstrapConfig.Builder().applyConfig(config).build();
            final ControlplanePolicy controlplanePolicy = ControlplanePolicy.from(config);
            final boolean readinessEnabled = ConfigResolver.resolveReadinessEnabled(config);
            final boolean cleanWorktreeRequired =
                ConfigResolver.resolveCleanWorktreeRequired(config);
            final boolean bboxFailOnError = ConfigResolver.resolveBboxFailOnError(config);
            final Consumer<String> readinessLogger = message -> SeedLog.info("readiness", message);
            final Map<String, Object> outputs =
                instance.bootstrapAndCollectOutputs(
                    bootstrapConfig,
                    controlplanePolicy,
                    readinessEnabled,
                    cleanWorktreeRequired,
                    bboxFailOnError,
                    readinessLogger);
            outputs.forEach(context::export);
          } finally {
            SeedLog.clearPulumiLogSink();
          }
        });
  }

  private void runStandalone() {
    final BootstrapConfig bootstrapConfig = new BootstrapConfig.Builder().build();
    final ControlplanePolicy controlplanePolicy = ControlplanePolicy.defaults();
    final Consumer<String> readinessLogger = message -> SeedLog.info("readiness", message);
    final Map<String, Object> outputs =
        bootstrapAndCollectOutputs(
            bootstrapConfig, controlplanePolicy, true, true, true, readinessLogger);
    SeedLog.info(
        "standalone",
        "Pulumi engine not detected (missing PULUMI_MONITOR). Running in standalone mode.");
    SeedLog.info("standalone", "Bootstrap outputs:");
    outputs.forEach((key, value) -> SeedLog.info("standalone", key + "=" + value));
  }

  private static boolean isPulumiEngineAvailable() {
    final String monitor = System.getenv("PULUMI_MONITOR");
    return monitor != null && !monitor.isBlank();
  }

  private boolean isPulumiMode() {
    return isPulumiEngineAvailable();
  }

  private Map<String, Object> bootstrapAndCollectOutputs(
      BootstrapConfig config,
      ControlplanePolicy policy,
      boolean readinessEnabled,
      boolean cleanWorktreeRequired,
      boolean bboxFailOnError,
      Consumer<String> readinessLogger) {

    return BootstrapExecutionPipeline.start(
            config,
            policy,
            readinessLogger,
            isPulumiMode(),
            bboxOrchestrator,
            resourceManager,
            outputBuilder)
        .withPreflight(readinessEnabled, cleanWorktreeRequired)
        .reconcileBbox(bboxFailOnError)
        .bootstrapIncus()
        .launchSystemdAdapter()
        .createResources()
        .buildOutputs();
  }
}
