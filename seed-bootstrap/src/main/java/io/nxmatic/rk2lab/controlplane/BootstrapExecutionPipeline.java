package io.nxmatic.rk2lab.controlplane;

import com.pulumi.deployment.Deployment;
import io.nxmatic.rk2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rk2lab.controlplane.incus.IncusResourceBootstrap;
import io.nxmatic.rk2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rk2lab.controlplane.policy.EntryGatePolicyEnforcer;
import io.nxmatic.rk2lab.controlplane.resources.ResourceManager;
import io.nxmatic.rk2lab.controlplane.systemd.SeedSystemdAdapterEndpointGate;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Fluent builder for the complete bootstrap execution pipeline.
 *
 * <p>Chains all bootstrap stages into a readable pipeline:
 *
 * <pre>
 * BootstrapExecutionPipeline.start(config, policy, logger)
 *     .withPreflight(readinessEnabled, cleanWorktreeRequired)
 *     .reconcileBbox(bboxFailOnError)
 *     .bootstrapIncus()
 *     .launchSystemdAdapter()
 *     .createResources()
 *     .buildOutputs()
 *     .execute();
 * </pre>
 */
public final class BootstrapExecutionPipeline {

  private final BootstrapConfig config;
  private final ControlplanePolicy policy;
  private final Consumer<String> readinessLogger;
  private final boolean pulumiMode;
  private final BboxReconciliationOrchestrator bboxOrchestrator;
  private final ResourceManager resourceManager;
  private final OutputBuilder outputBuilder;

  // Pipeline state
  private boolean readinessEnabled;
  private BboxReconciliationOrchestrator.ReconciliationResult bboxResult;
  private IncusResourceBootstrap.BootstrapResult bootstrapResult;
  private Map<String, Object> systemdAdapterLaunchSummary;
  private ResourceManager.ResourceCreationResult resourceResult;

  private BootstrapExecutionPipeline(
      BootstrapConfig config,
      ControlplanePolicy policy,
      Consumer<String> readinessLogger,
      boolean pulumiMode,
      BboxReconciliationOrchestrator bboxOrchestrator,
      ResourceManager resourceManager,
      OutputBuilder outputBuilder) {
    this.config = config;
    this.policy = policy;
    this.readinessLogger = readinessLogger;
    this.pulumiMode = pulumiMode;
    this.bboxOrchestrator = bboxOrchestrator;
    this.resourceManager = resourceManager;
    this.outputBuilder = outputBuilder;
  }

  /** Creates a new pipeline builder. */
  public static BootstrapExecutionPipeline start(
      BootstrapConfig config,
      ControlplanePolicy policy,
      Consumer<String> readinessLogger,
      boolean pulumiMode,
      BboxReconciliationOrchestrator bboxOrchestrator,
      ResourceManager resourceManager,
      OutputBuilder outputBuilder) {
    return new BootstrapExecutionPipeline(
        config,
        policy,
        readinessLogger,
        pulumiMode,
        bboxOrchestrator,
        resourceManager,
        outputBuilder);
  }

  /** Executes preflight checks: entry gates and runtime command verification. */
  public BootstrapExecutionPipeline withPreflight(
      boolean readinessEnabled, boolean cleanWorktreeRequired) {
    this.readinessEnabled = readinessEnabled;
    EntryGatePolicyEnforcer.enforceAll(config.localWorktreePath(), cleanWorktreeRequired);
    RuntimeCommandPreflight.enforceRequiredCommands(
        java.util.List.of("ssh", "kubectl"), readinessLogger);
    RuntimeCommandPreflight.enforceRemoteCommandAvailable(
        config.imageBuilderHost(), "incus", readinessLogger);
    return this;
  }

  /** Reconciles bbox DHCP reservations. */
  public BootstrapExecutionPipeline reconcileBbox(boolean failOnError) {
    this.bboxResult = bboxOrchestrator.reconcile(config.localWorktreePath(), failOnError);
    return this;
  }

  /** Bootstraps the Incus instance. */
  public BootstrapExecutionPipeline bootstrapIncus() {
    this.bootstrapResult = new IncusResourceBootstrap(config, policy).apply();
    return this;
  }

  /** Launches the systemd adapter endpoint. */
  public BootstrapExecutionPipeline launchSystemdAdapter() {
    if (pulumiMode && Deployment.getInstance().isDryRun()) {
      this.systemdAdapterLaunchSummary = SeedSystemdAdapterEndpointGate.deferredPreview(config);
    } else {
      this.systemdAdapterLaunchSummary =
          SeedSystemdAdapterEndpointGate.ensureReachable(config, readinessLogger);
    }
    return this;
  }

  /** Creates bootstrap resources using the pipeline. */
  public BootstrapExecutionPipeline createResources() {
    this.resourceResult =
        resourceManager.createResources(
            config,
            policy,
            readinessEnabled,
            readinessLogger,
            bootstrapResult,
            systemdAdapterLaunchSummary,
            pulumiMode);
    return this;
  }

  /** Builds and returns the final output map. */
  public Map<String, Object> buildOutputs() {
    return outputBuilder.buildOutputs(
        config, policy, bootstrapResult, bboxResult, systemdAdapterLaunchSummary, resourceResult);
  }

  /** Executes the entire pipeline and returns outputs (alias for buildOutputs). */
  public Map<String, Object> execute() {
    return buildOutputs();
  }
}
