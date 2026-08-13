package io.seedmatic.rke2lab.doctor.contract;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * An intervention — any actor changing the world: the Pulumi engine applied a prescription, the
 * operator fixed something out-of-band, or the system detected external drift. It records WHAT
 * changed and WHO did it, so the medical record can stop crediting prescriptions with fixes the
 * operator actually performed. The {@link #problem} tags the intervention so it can be joined to
 * the Problem it explains. The {@link #prescriptionRef} is present when the intervention was
 * engine-driven (Pulumi applied its own prescription); absent when operator-manual or
 * external-change-detected. The {@link #details} carry any extra context (e.g., remediation window,
 * unit name) that the provenance specialist needs to reconstruct what happened.
 */
public record Intervention(
    Provenance provenance,
    Instant when,
    String what,
    ProblemRef problem,
    Optional<RemediationProgramRef> prescriptionRef,
    Map<String, Object> details) {

  public Intervention {
    prescriptionRef = prescriptionRef == null ? Optional.empty() : prescriptionRef;
    details = details == null ? Map.of() : Map.copyOf(details);
  }
}
