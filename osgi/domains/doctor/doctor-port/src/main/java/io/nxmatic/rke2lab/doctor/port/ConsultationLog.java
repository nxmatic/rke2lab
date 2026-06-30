package io.nxmatic.rke2lab.doctor.port;

import io.nxmatic.rke2lab.world.gateway.port.Document;
import java.util.ArrayList;
import java.util.List;

/**
 * The shared, caller-owned collector of consultation {@link Document}s — a sibling of the runbook's
 * {@code ReportModel}, threaded through every checkpoint the same way. A checkpoint records its
 * consultation Document here on a raised symptom, so the doctor's reasoning (narration +
 * diagnosisAdoc + the structured plan/observations/expectations sub-trees) is kept rather than
 * computed-logged-then-dropped. Null-safe by construction: a checkpoint with no log falls back to a
 * discarded local one (inline log only), exactly as it does for an absent runbook model.
 *
 * <p>The consultation crosses the host↔OSGi seam as a neutral {@link Document} (the doctor reasons
 * OSGi-side; the host transports the opaque payload), so this carrier holds NO doctor type — the
 * egress copies the structured sub-trees to the same Pulumi output keys, and the runbook reads the
 * rendered {@code diagnosisAdoc} string, neither needing a {@code doctor.records} import.
 */
public final class ConsultationLog {

  private final List<Document> consultations = new ArrayList<>();

  public void record(Document consultation) {
    consultations.add(consultation);
  }

  public List<Document> consultations() {
    return List.copyOf(consultations);
  }
}
