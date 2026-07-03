package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.cluster.port.ClusterReadinessContact;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.incus.IncusResourceBootstrap;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.resources.ResourceManager;
import io.nxmatic.rke2lab.controlplane.resources.ResourceManager.ResourceCreationResult;
import io.nxmatic.rke2lab.controlplane.systemd.SeedSystemdAdapterRuntimeStatusSnapshot;
import io.nxmatic.rke2lab.doctor.port.ConsultationLog;
import io.nxmatic.rke2lab.doctor.port.ConsultingService;
import io.nxmatic.rke2lab.pipeline.Topic;
import io.nxmatic.rke2lab.pulumi.edge.LiveGate;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Bootstrap-resources topic — the FAN-IN: its two flux inputs ({@code bootstrap}, {@code
 * systemdAdapterLaunch}) are outputs of upstream topics, read off the accumulator by the transition
 * and handed in as concrete values (no {@code Supplier} back-reference). Pushes the created
 * resources through its {@link Sink}.
 */
public final class ResourcesTopic implements Topic.Execution {

  private final ResourceManager resourceManager;
  private final BootstrapConfig config;
  private final ControlplanePolicy policy;
  private final boolean readinessEnabled;
  private final boolean pulumiMode;
  private final LiveGate gate;
  private final Consumer<String> readinessLogger;
  private final Optional<ReportModel> runbook;
  private final Optional<ConsultationLog> consultations;
  private final ConsultingService doctor;
  private final SeedSystemdAdapterRuntimeStatusSnapshot systemdRuntimeStatus;
  private final ClusterReadinessContact clusterReadinessContact;
  private final IncusResourceBootstrap.BootstrapResult bootstrapResult;
  private final Map<String, Object> systemdAdapterLaunch;
  private final Sink sink;

  public ResourcesTopic(
      ResourceManager resourceManager,
      BootstrapConfig config,
      ControlplanePolicy policy,
      boolean readinessEnabled,
      boolean pulumiMode,
      LiveGate gate,
      Consumer<String> readinessLogger,
      Optional<ReportModel> runbook,
      Optional<ConsultationLog> consultations,
      ConsultingService doctor,
      SeedSystemdAdapterRuntimeStatusSnapshot systemdRuntimeStatus,
      ClusterReadinessContact clusterReadinessContact,
      IncusResourceBootstrap.BootstrapResult bootstrapResult,
      Map<String, Object> systemdAdapterLaunch,
      Sink sink) {
    this.resourceManager = resourceManager;
    this.config = config;
    this.policy = policy;
    this.readinessEnabled = readinessEnabled;
    this.pulumiMode = pulumiMode;
    this.gate = gate;
    this.readinessLogger = readinessLogger;
    this.runbook = runbook;
    this.consultations = consultations;
    this.doctor = doctor;
    this.systemdRuntimeStatus = systemdRuntimeStatus;
    this.clusterReadinessContact = clusterReadinessContact;
    this.bootstrapResult = bootstrapResult;
    this.systemdAdapterLaunch = systemdAdapterLaunch;
    this.sink = sink;
  }

  /** The write-face of the resources topic. */
  public interface Sink extends Topic.Sink {
    void resources(ResourceCreationResult result);
  }

  @Override
  public String role() {
    return "bootstrap resources";
  }

  public ResourcesTopic createAll() {
    sink.resources(
        resourceManager.createResources(
            config,
            policy,
            readinessEnabled,
            readinessLogger,
            runbook,
            consultations,
            doctor,
            systemdRuntimeStatus,
            clusterReadinessContact,
            bootstrapResult,
            systemdAdapterLaunch,
            pulumiMode,
            gate));
    return this;
  }
}
