package io.nxmatic.rke2lab.doctor.records;

import io.nxmatic.rke2lab.world.gateway.port.Checkpoint;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One consultation of one checkpoint: what the patient brought to the doctor (the {@link
 * Observation}s it raised while self-observing — one for a single-probe checkpoint, several for a
 * multi-phase one) and what the doctor produced (the {@link RemediationPlan}). Produced only when
 * the patient consults — i.e. on a raised symptom (the reactive-consultation model); a healthy
 * checkpoint raises none and produces no report.
 *
 * <p>This is the artifact the medical record (the runbook DAG, layer 3) will aggregate per patient.
 * Today the plan is computed in the checkpoint's {@code consultDoctor(...)}, logged, then dropped;
 * carrying it here is what stops that loss — the prerequisite for "the doctor sees the records".
 */
public record ConsultationReport(
    String checkpointId, List<Observation> observations, RemediationPlan plan) {

  /**
   * The Pulumi output key under which a checkpoint registers its report — the single source of
   * truth shared by the writers ({@code SystemdAdapterResource}, {@code ClusterReadinessResource})
   * and the reader ({@code MedicalRecordReader}), so a write/read drift cannot silently break
   * reconstruction.
   */
  public static final String OUTPUT_KEY = "consultationReport";

  public ConsultationReport {
    observations = observations == null ? List.of() : List.copyOf(observations);
  }

  /** The symptom the doctor diagnosed (the plan's subject). */
  public Symptom symptom() {
    return plan.symptom();
  }

  /**
   * What this consultation predicts: one {@link Expectation} per prescription — that the diagnosed
   * symptom resolves by the next visit ({@link ResolutionPredicate}). A plan with no prescription
   * predicts nothing (empty list). Pure derivation; the caller supplies the run instant. Fails fast
   * on an unknown checkpointId (single-source-of-truth discipline).
   */
  public List<Expectation> expectations(Instant recordedAt) {
    final ProblemRef problem =
        ProblemRef.of(Checkpoint.fromSlug(checkpointId).orElseThrow(), symptom());
    return plan.prescriptions().stream()
        .map(
            prescription ->
                new Expectation(
                    problem,
                    prescription.programRef(),
                    new ResolutionPredicate(symptom()),
                    recordedAt))
        .toList();
  }

  /** Flat map view; {@code observations} and {@code plan} are themselves flat map views. */
  public Map<String, Object> toOutputMap() {
    final LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    map.put("checkpointId", checkpointId);
    map.put("observations", observations.stream().map(Observation::toOutputMap).toList());
    map.put("plan", plan.toOutputMap());
    return map;
  }
}
