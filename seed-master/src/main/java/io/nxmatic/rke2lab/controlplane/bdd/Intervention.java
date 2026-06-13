package io.nxmatic.rke2lab.controlplane.bdd;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * An intervention — any actor changing the world: the Pulumi engine applied a prescription, the
 * operator fixed something out-of-band, or the system detected external drift. It records WHAT
 * changed and WHO did it, so the medical record can stop crediting prescriptions with fixes the
 * operator actually performed. The {@link #prescriptionRef} is present when the intervention was
 * engine-driven (Pulumi applied its own prescription); absent when operator-manual or
 * external-change-detected. The {@link #details} carry any extra context (e.g., remediation window,
 * unit name) that the provenance specialist needs to reconstruct what happened.
 */
public record Intervention(
    Provenance provenance,
    Instant when,
    String what,
    Optional<RemediationProgramRef> prescriptionRef,
    Map<String, Object> details) {

  public Intervention {
    prescriptionRef = prescriptionRef == null ? Optional.empty() : prescriptionRef;
    details = details == null ? Map.of() : Map.copyOf(details);
  }

  /** Flat map view; enum refs are kebab-case ids, never enum names. */
  public Map<String, Object> toOutputMap() {
    final LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    map.put("provenance", provenance.id());
    map.put("when", when.toString());
    map.put("what", what);
    prescriptionRef.ifPresent(ref -> map.put("prescriptionRef", ref.id()));
    map.putAll(details);
    return Map.copyOf(map);
  }
}
