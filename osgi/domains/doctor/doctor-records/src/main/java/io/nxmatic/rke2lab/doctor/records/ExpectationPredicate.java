package io.nxmatic.rke2lab.doctor.records;

import java.util.Map;
import java.util.Optional;

/**
 * A predicate about what a prescription expects to be true at the next visit. Today the only
 * implementation is {@link ResolutionPredicate} (the symptom resolved). But a future drift
 * specialist will want a richer predicate (a fingerprint of the observed state to diff). Modeled as
 * a sealed interface — the explicit extension seam — so future implementations (e.g.,
 * FingerprintPredicate) can slot in without touching callers.
 */
public sealed interface ExpectationPredicate permits ResolutionPredicate {

  /** Did the prediction hold at the following visit? */
  boolean heldAt(Visit nextVisit);

  /**
   * Flat map view for persistence. MUST include a discriminator key {@code "kind"} so {@link
   * #fromOutputMap} can dispatch to the right implementation.
   */
  Map<String, Object> toOutputMap();

  /**
   * Parse a flat map into a typed predicate. Non-map/null/missing-kind → empty. Reads {@code
   * "kind"}, dispatches to the appropriate implementation's parser. Unknown kind → empty.
   */
  static Optional<ExpectationPredicate> fromOutputMap(Object raw) {
    if (!(raw instanceof Map<?, ?> uncheckedMap)) {
      return Optional.empty();
    }
    @SuppressWarnings("unchecked")
    final Map<String, Object> map = (Map<String, Object>) uncheckedMap;

    final Object kindRaw = map.get("kind");
    if (!(kindRaw instanceof String kind)) {
      return Optional.empty();
    }

    return switch (kind) {
      case "resolution" ->
          ResolutionPredicate.fromOutputMap(map).map(p -> (ExpectationPredicate) p);
      default -> Optional.empty();
    };
  }
}
