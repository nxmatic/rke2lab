package io.nxmatic.rke2lab.controlplane.bdd;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The SOAP "assessment" — a specialist's reasoning about what they found. Always present in a reply
 * (a specialist may have no prescription to offer but always explains why). Self-describing via the
 * {@link #schemaRef} the specialist declares — open set, no registry. The {@link #payload} carries
 * the structured details; the {@link #summary} is the human "why" (never null or blank — a reply
 * without reasoning is incomplete state).
 */
public record Assessment(SchemaRef schemaRef, Map<String, Object> payload, String summary) {

  public Assessment {
    if (schemaRef == null) {
      throw new IllegalArgumentException("schemaRef cannot be null");
    }
    if (summary == null || summary.isBlank()) {
      throw new IllegalArgumentException("summary cannot be null or blank");
    }
    payload = payload == null ? Map.of() : Map.copyOf(payload);
  }

  public static Assessment of(SchemaRef schemaRef, Map<String, Object> payload, String summary) {
    return new Assessment(schemaRef, payload, summary);
  }

  /** Flat map view; {@code schemaRef} is the schema id string, not the SchemaRef object. */
  public Map<String, Object> toOutputMap() {
    final LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    map.put("schemaRef", schemaRef.id());
    map.put("payload", payload);
    map.put("summary", summary);
    return map;
  }
}
