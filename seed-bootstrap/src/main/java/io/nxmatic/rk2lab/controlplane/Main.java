package io.nxmatic.rk2lab.controlplane;

import com.pulumi.Config;
import com.pulumi.Pulumi;
import io.nxmatic.rk2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rk2lab.controlplane.config.ConfigResolver;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rk2lab.controlplane.pipeline.BootstrapOptions;
import io.nxmatic.rk2lab.controlplane.pipeline.BootstrapPipeline;
import io.nxmatic.rk2lab.controlplane.pipeline.OutputBuilder;
import io.nxmatic.rk2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rk2lab.controlplane.resources.ResourceManager;
import java.util.Map;
import java.util.function.Consumer;

/** Entry point for the Pulumi management-cluster bootstrap program. */
public final class Main {

  private final boolean pulumiMode;
  private final BboxReconciliationOrchestrator bboxOrchestrator;
  private final ResourceManager resourceManager;
  private final OutputBuilder outputBuilder;

  private Main(boolean pulumiMode) {
    this.pulumiMode = pulumiMode;
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
            final BootstrapOptions options =
                BootstrapOptions.builder()
                    .readinessEnabled(ConfigResolver.resolveReadinessEnabled(config))
                    .cleanWorktreeRequired(ConfigResolver.resolveCleanWorktreeRequired(config))
                    .bboxFailOnError(ConfigResolver.resolveBboxFailOnError(config))
                    .build();
            final Consumer<String> readinessLogger = message -> SeedLog.info("readiness", message);
            final Map<String, Object> outputs =
                instance.bootstrapAndCollectOutputs(
                    bootstrapConfig, controlplanePolicy, options, readinessLogger);
            outputs.forEach(context::export);
          } finally {
            SeedLog.clearPulumiLogSink();
          }
        });
  }

  private void runStandalone() {
    final BootstrapConfig bootstrapConfig = new BootstrapConfig.Builder().build();
    final ControlplanePolicy controlplanePolicy = ControlplanePolicy.defaults();
    final BootstrapOptions options = BootstrapOptions.builder().build();
    final Consumer<String> readinessLogger = message -> SeedLog.info("readiness", message);
    final Map<String, Object> outputs =
        bootstrapAndCollectOutputs(bootstrapConfig, controlplanePolicy, options, readinessLogger);
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

  private Map<String, Object> bootstrapAndCollectOutputs(
      BootstrapConfig config,
      ControlplanePolicy policy,
      BootstrapOptions options,
      Consumer<String> readinessLogger) {

    final BootstrapPipeline.ComponentBoundPipeline ready =
        BootstrapPipeline.forCluster(config, policy)
            .withOptions(options)
            .using(bboxOrchestrator, resourceManager, outputBuilder);

    final BootstrapPipeline.AwaitingPreflight primed =
        pulumiMode
            ? ready.runningInPulumi(readinessLogger)
            : ready.runningStandalone(readinessLogger);

    return primed
        .during(
            "preflight",
            preflight ->
                preflight
                    .enforceEntryGates()
                    .requireLocalCommands("ssh", "kubectl")
                    .requireRemoteCommand("incus"))
        .then()
        .during("bbox reconciliation", bbox -> bbox.reconcileReservations())
        .then()
        .during("incus provisioning", incus -> incus.provisionInstance())
        .then()
        .during("systemd adapter", adapter -> adapter.launch())
        .then()
        .during("bootstrap resources", resources -> resources.createAll())
        .orFailWith((topic, cause) -> SeedLog.error("pipeline", topic + ": " + cause.getMessage()))
        .collectOutputs();
  }
}
