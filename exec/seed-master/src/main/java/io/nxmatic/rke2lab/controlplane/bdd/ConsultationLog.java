package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.ArrayList;
import java.util.List;

/**
 * The host's session collector of consultation {@link SeedEnvelope}s — a sibling of the runbook's
 * {@code ReportModel}, threaded through every checkpoint the same way. A checkpoint records the
 * envelope the doctor handed back on a raised symptom, so the doctor's reasoning is kept for this
 * run rather than computed-logged-then-dropped. Null-safe by construction: a checkpoint with no log
 * falls back to a discarded local one (inline log only), exactly as it does for an absent runbook
 * model.
 *
 * <p>A pure TRANSPORT buffer, not a doctor type: it holds neutral {@link SeedEnvelope}s (the doctor
 * reasons OSGi-side; the host only transports the opaque payload). It lives host-side because the
 * host is the one playing the scenario for now; when the scenario moves in-container, the doctor
 * keeps its own consultation record OSGi-side and this buffer disappears. The host never OPENS an
 * envelope it holds here — to file a piece it asks the broker (a graft {@code sow}), it does not
 * decode.
 */
public final class ConsultationLog {

  private final List<SeedEnvelope> consultations = new ArrayList<>();

  public void record(SeedEnvelope consultation) {
    consultations.add(consultation);
  }

  public List<SeedEnvelope> consultations() {
    return List.copyOf(consultations);
  }
}
