package io.nxmatic.rke2lab.controlplane.pipeline;

import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rke2lab.controlplane.bdd.ConsultationLog;
import io.nxmatic.rke2lab.controlplane.bdd.MedicalRecordRegistry;
import io.nxmatic.rke2lab.controlplane.bdd.Patient;
import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.incus.IncusResourceBootstrap;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.resources.ResourceManager;
import java.util.Map;
import java.util.function.Consumer;

final class PipelineState {

  final BootstrapConfig config;
  final ControlplanePolicy policy;
  BootstrapOptions options;
  Consumer<String> readinessLogger;
  boolean pulumiMode;

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
   * The live doctor's standing access to patient records and the patient (this stack) under care.
   * Built once per run and held by every {@link io.nxmatic.rke2lab.controlplane.bdd.Generalist} the
   * stages construct, so the record retrieval is the first act of each consultation.
   */
  MedicalRecordRegistry records;

  Patient currentPatient;

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
