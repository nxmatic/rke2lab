package io.seedmatic.rke2lab.doctor.contract;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A predicate about what a prescription expects to be true at the next visit. Today the only
 * implementation is {@link ResolutionPredicate} (the symptom resolved). But a future drift
 * specialist will want a richer predicate (a fingerprint of the observed state to diff). Modeled as
 * a sealed interface — the explicit extension seam — so future implementations (e.g.,
 * FingerprintPredicate) can slot in without touching callers.
 *
 * <p>The codec (de)serializes the ADT polymorphically: a {@code "kind"} discriminator property
 * names the implementation ({@code "resolution"} → {@link ResolutionPredicate}). A future subtype
 * registers by adding a {@link JsonSubTypes.Type} entry here.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(@JsonSubTypes.Type(value = ResolutionPredicate.class, name = "resolution"))
public sealed interface ExpectationPredicate permits ResolutionPredicate {

  /** Did the prediction hold at the following visit? */
  boolean heldAt(Visit nextVisit);
}
