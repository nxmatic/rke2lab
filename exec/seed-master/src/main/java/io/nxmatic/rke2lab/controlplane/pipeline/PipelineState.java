package io.nxmatic.rke2lab.controlplane.pipeline;

import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.cluster.port.ClusterReadinessContact;
import io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.incus.IncusResourceBootstrap;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.resources.ResourceManager;
import io.nxmatic.rke2lab.controlplane.systemd.SeedSystemdAdapterRuntimeStatusSnapshot;
import io.nxmatic.rke2lab.doctor.port.ConsultationLog;
import io.nxmatic.rke2lab.doctor.port.ConsultingService;
import io.nxmatic.rke2lab.osgi.runtime.BootedFramework;
import io.nxmatic.rke2lab.pipeline.FluentTopicRunner;
import io.nxmatic.rke2lab.pipeline.OnFailure;
import java.util.Map;
import java.util.function.Consumer;

final class PipelineState {

  final BootstrapConfig config;
  final ControlplanePolicy policy;
  BootstrapOptions options;
  Consumer<String> readinessLogger;
  boolean pulumiMode;

  /**
   * The embedded OSGi framework booted once for this run (see {@code ClusterSeedTopic}). Threaded
   * to the stages that read manifests-world services so they read them from the booted registry.
   */
  BootedFramework bootedFramework;

  /**
   * The runbook model, owned by the caller and threaded through every checkpoint so each records
   * its scenario into one shared model (rather than the discarded per-stage model). Null until the
   * caller calls {@code recordingInto}; checkpoints fall back to a local model when absent.
   */
  ReportModel runbook;

  /**
   * The consultation log, owned by the caller and threaded through every checkpoint so each records
   * its doctor consultation (the raised observations + the plan) into one shared log instead of
   * dropping the plan after the inline log. Null until {@code recordingInto}; checkpoints fall back
   * to a discarded local one when absent. In-memory only — does not touch the Pulumi outputs.
   */
  ConsultationLog consultations;

  /**
   * The doctor's consulting contract for this run, obtained host-side by admitting the patient into
   * the OSGi {@code HealthSystem} (the host publishes the EHR + ledger, then {@code
   * awaitService(HealthSystem).admit}). Built once at the readiness transition; the stages consult
   * it without naming the hidden actors behind it.
   */
  ConsultingService doctor;

  /**
   * The systemd runtime-status probe for this run, resolved once from the booted OSGi registry (the
   * dbus-systemd-edge {@code @Component} implementing {@code SystemdRuntimeProbe}) and wrapped as
   * this instance. Threaded to the readiness sites that take a status snapshot, so none of them
   * reaches the edge statically.
   */
  SeedSystemdAdapterRuntimeStatusSnapshot systemdRuntimeStatus;

  /**
   * The cluster-readiness contact for this run, resolved once from the booted OSGi registry (the
   * cluster-edge {@code @Component} implementing {@code ClusterReadinessContact}). Threaded to the
   * readiness probe, which the host orchestration wraps in its retry loops — so the host never
   * reaches the edge statically.
   */
  ClusterReadinessContact clusterReadinessContact;

  /**
   * The readiness authority for this run, resolved once from the booted OSGi registry (the
   * doctor-core {@code @Component} implementing {@code ReadinessAuthority}). Threaded to the stages
   * that build checkpoint Documents and read verdict actions — so the host never reasons on
   * Severity.
   */
  io.nxmatic.rke2lab.world.gateway.port.ReadinessAuthority readinessAuthority;

  BboxReconciliationOrchestrator bboxOrchestrator;
  ResourceManager resourceManager;
  OutputBuilder outputBuilder;

  BboxReconciliationOrchestrator.ReconciliationResult bboxResult;
  IncusResourceBootstrap.BootstrapResult bootstrapResult;
  Map<String, Object> systemdAdapterLaunchSummary;
  ResourceManager.ResourceCreationResult resourceResult;

  OnFailure onFailure;
  final FluentTopicRunner runner = new FluentTopicRunner("pipeline");

  PipelineState(BootstrapConfig config, ControlplanePolicy policy) {
    this.config = config;
    this.policy = policy;
  }
}
