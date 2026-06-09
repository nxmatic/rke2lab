package io.nxmatic.rke2lab.controlplane.bdd;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

  /**
   * The Pulumi output key under which a checkpoint registers its report — the single source of
   * truth shared by the writers ({@code SystemdAdapterResource}, {@code ClusterReadinessResource})
   * and the reader ({@code MedicalRecordReader}), so a write/read drift cannot silently break
   * reconstruction.
   */
  public static final String OUTPUT_KEY = "consultationReport";

  public ConsultationReport {
    dossiers = dossiers == null ? List.of() : List.copyOf(dossiers);
  }

  /** The symptom the doctor diagnosed (the plan's subject). */
  public Symptom symptom() {
    return plan.symptom();
  }

  /** Flat map view; {@code dossiers} and {@code plan} are themselves flat map views. */
  public Map<String, Object> toOutputMap() {
    final LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    map.put("checkpointId", checkpointId);
    map.put("dossiers", dossiers.stream().map(Dossier::toOutputMap).toList());
    map.put("plan", plan.toOutputMap());
    return map;
  }
}
