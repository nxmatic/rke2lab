package io.seedmatic.rke2lab.doctor.contract;

import java.util.Map;

/**
 * The SOAP "assessment" — a specialist's reasoning about what they found. Always present in a reply
 * (a specialist may have no prescription to offer but always explains why). Self-describing via the
 * {@link #schemaRef} the specialist declares — open set, no registry. The {@link #payload} carries
 * the structured details; the {@link #summary} is the human "why" (never null or blank — a reply
 * without reasoning is incomplete state).
 *
 * <p>The codec (de)serializes it natively: {@code schemaRef} as its annotated id string, {@code
 * payload} as an open map, {@code summary} as the prose. A malformed decode (blank summary, missing
 * schemaRef) throws the compact-ctor guard, caught at the fromMap boundary — the enclosing entry
 * degrades, keeping the string reader's tolerance.
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
}
