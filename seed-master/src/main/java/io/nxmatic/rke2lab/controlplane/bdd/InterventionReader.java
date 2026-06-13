package io.nxmatic.rke2lab.controlplane.bdd;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The tolerant inverse of {@link Intervention#toOutputMap()}: rebuilds a typed {@link Intervention}
 * from the flat, string-keyed map a ledger entry registered as a Pulumi output. It never throws —
 * malformed input yields {@link Optional#empty()} — so a stale or partially-written intervention
 * degrades instead of crashing the read.
 *
 * <p>Additive by design: the schema evolves by adding keys, never by a version number. The reader
 * pulls each known key by name and ignores the rest; unknown keys are preserved in the {@code
 * details} map, so a key the producer adds tomorrow survives a round-trip through a reader written
 * today.
 */
final class InterventionReader {

  private InterventionReader() {}

  /**
   * Two keys are HARD requirements: {@code provenance} (must parse to a valid {@link Provenance})
   * and {@code when} (must parse to an {@link Instant}). The {@code what} string defaults to empty
   * if absent. The {@code prescriptionRef} is optional — absence yields {@link Optional#empty()},
   * as does an unparseable value. Everything else in the map goes into {@code details} — the
   * additive contract.
   */
  public static Optional<Intervention> fromOutputMap(Object raw) {
    if (!(raw instanceof Map<?, ?> uncheckedMap)) {
      return Optional.empty();
    }
    @SuppressWarnings("unchecked")
    final Map<String, Object> map = (Map<String, Object>) uncheckedMap;

    // provenance is required
    final Optional<Provenance> provenance = Provenance.parse(stringOrEmpty(map.get("provenance")));
    if (provenance.isEmpty()) {
      return Optional.empty();
    }

    // when is required
    final Instant when;
    try {
      when = Instant.parse(stringOrEmpty(map.get("when")));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }

    // what defaults to empty string
    final String what = stringOrEmpty(map.get("what"));

    // prescriptionRef is optional — absent or unparseable → empty
    final Optional<RemediationProgramRef> prescriptionRef =
        RemediationProgramRef.parse(stringOrEmpty(map.get("prescriptionRef")));

    // details = everything else: drop the four canonical keys, keep all the rest
    final LinkedHashMap<String, Object> details = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      final String key = entry.getKey();
      if (key.equals("provenance")
          || key.equals("when")
          || key.equals("what")
          || key.equals("prescriptionRef")) {
        continue;
      }
      details.put(key, entry.getValue());
    }

    return Optional.of(new Intervention(provenance.get(), when, what, prescriptionRef, details));
  }

  private static String stringOrEmpty(Object value) {
    return value instanceof String s ? s : "";
  }
}
