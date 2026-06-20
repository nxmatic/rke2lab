package io.nxmatic.rke2lab.controlplane.pipeline;

import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rke2lab.controlplane.bdd.ConsultationLog;
import io.nxmatic.rke2lab.controlplane.bdd.HealthSystem;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.incus.IncusResourceBootstrap;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.resources.ResourceManager;
import io.nxmatic.rke2lab.osgi.runtime.OsgiRuntime;
import java.util.Map;
import java.util.function.Consumer;

final class PipelineState {

  final BootstrapConfig config;
  final ControlplanePolicy policy;
  BootstrapOptions options;
  Consumer<String> readinessLogger;
  boolean pulumiMode;

  /**
   * The embedded OSGi framework booted once for this run (see {@code BootstrapStage}), or null when
   * the process carries no embedded bundles (standalone/tests fall back to ServiceLoader). Threaded
   * to the stages that read manifests-world services so they read them from the booted registry.
   */
  OsgiRuntime osgiRuntime;

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
   * The doctor's keystone for this run: holds the records registry + grant policy, employs the
   * clinicians, admits the patient under care (this stack). Built once at the readiness transition;
   * the stages consult the generalist it employs.
   */
  HealthSystem healthSystem;

  BboxReconciliationOrchestrator bboxOrchestrator;
  ResourceManager resourceManager;
  OutputBuilder outputBuilder;

  BboxReconciliationOrchestrator.ReconciliationResult bboxResult;
  IncusResourceBootstrap.BootstrapResult bootstrapResult;
  Map<String, Object> systemdAdapterLaunchSummary;
  ResourceManager.ResourceCreationResult resourceResult;

  OnFailure onFailure;

  PipelineState(BootstrapConfig config, ControlplanePolicy policy) {
    this.config = config;
    this.policy = policy;
  }
}
