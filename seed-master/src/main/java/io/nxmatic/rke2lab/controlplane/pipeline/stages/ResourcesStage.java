package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.controlplane.bdd.ConsultationLog;
import io.nxmatic.rke2lab.controlplane.bdd.Generalist;
import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.incus.IncusResourceBootstrap;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.resources.ResourceManager;
import io.nxmatic.rke2lab.controlplane.resources.ResourceManager.ResourceCreationResult;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ResourcesStage {

  private final ResourceManager resourceManager;
  private final BootstrapConfig config;
  private final ControlplanePolicy policy;
  private final boolean readinessEnabled;
  private final boolean pulumiMode;
  private final Consumer<String> readinessLogger;
  private final ReportModel runbook;
  private final ConsultationLog consultations;
  private final Generalist generalist;
  private final Supplier<IncusResourceBootstrap.BootstrapResult> bootstrapResultSupplier;
  private final Supplier<Map<String, Object>> systemdAdapterLaunchSupplier;
  private final Consumer<ResourceCreationResult> sink;

  public ResourcesStage(
      ResourceManager resourceManager,
      BootstrapConfig config,
      ControlplanePolicy policy,
      boolean readinessEnabled,
      boolean pulumiMode,
      Consumer<String> readinessLogger,
      ReportModel runbook,
      ConsultationLog consultations,
      Generalist generalist,
      Supplier<IncusResourceBootstrap.BootstrapResult> bootstrapResultSupplier,
      Supplier<Map<String, Object>> systemdAdapterLaunchSupplier,
      Consumer<ResourceCreationResult> sink) {
    this.resourceManager = resourceManager;
    this.config = config;
    this.policy = policy;
    this.readinessEnabled = readinessEnabled;
    this.pulumiMode = pulumiMode;
    this.readinessLogger = readinessLogger;
    this.runbook = runbook;
    this.consultations = consultations;
    this.generalist = generalist;
    this.bootstrapResultSupplier = bootstrapResultSupplier;
    this.systemdAdapterLaunchSupplier = systemdAdapterLaunchSupplier;
    this.sink = sink;
  }

  public ResourcesStage createAll() {
    sink.accept(
        resourceManager.createResources(
            config,
            policy,
            readinessEnabled,
            readinessLogger,
            runbook,
            consultations,
            generalist,
            bootstrapResultSupplier.get(),
            systemdAdapterLaunchSupplier.get(),
            pulumiMode));
    return this;
  }
}
