package io.nxmatic.rke2lab.doctor.port;

import io.nxmatic.rke2lab.doctor.records.*;
import java.util.ArrayList;
import java.util.List;

/**
 * The shared, caller-owned collector of {@link ConsultationReport}s — a sibling of the runbook's
 * {@code ReportModel}, threaded through every checkpoint the same way. A checkpoint records its
 * consultation here on a raised symptom, so the doctor's {@link RemediationPlan} is kept rather
 * than computed-logged-then-dropped. Null-safe by construction: a checkpoint with no log falls back
 * to a discarded local one (inline log only), exactly as it does for an absent runbook model.
 *
 * <p>This is the in-memory accumulation the medical record (the runbook DAG, layer 3) will read; it
 * deliberately does NOT touch the Pulumi outputs (the Stage-B contract stays byte-identical).
 */
public final class ConsultationLog {

  private final List<ConsultationReport> consultations = new ArrayList<>();

  public void record(ConsultationReport report) {
    consultations.add(report);
  }

  public List<ConsultationReport> consultations() {
    return List.copyOf(consultations);
  }
}
