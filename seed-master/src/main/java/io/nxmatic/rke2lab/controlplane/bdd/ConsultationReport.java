package io.nxmatic.rke2lab.controlplane.bdd;

import java.util.List;

/**
 * One consultation of one checkpoint: what the patient brought to the doctor (the {@link Dossier}s
 * it raised while self-observing — one for a single-probe checkpoint, several for a multi-phase
 * one) and what the doctor produced (the {@link RemediationPlan}). Produced only when the patient
 * consults — i.e. on a raised symptom (the reactive-consultation model); a healthy checkpoint
 * raises none and produces no report.
 *
 * <p>This is the artifact the medical record (the runbook DAG, layer 3) will aggregate per patient.
 * Today the plan is computed in the checkpoint's {@code consultDoctor(...)}, logged, then dropped;
 * carrying it here is what stops that loss — the prerequisite for "the doctor sees the records".
 */
public record ConsultationReport(
    String checkpointId, List<Dossier> dossiers, RemediationPlan plan) {

  public ConsultationReport {
    dossiers = dossiers == null ? List.of() : List.copyOf(dossiers);
  }

  /** The symptom the doctor diagnosed (the plan's subject). */
  public Symptom symptom() {
    return plan.symptom();
  }
}
