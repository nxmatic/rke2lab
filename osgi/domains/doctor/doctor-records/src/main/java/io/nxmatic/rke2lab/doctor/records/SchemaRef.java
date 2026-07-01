package io.nxmatic.rke2lab.doctor.records;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/**
 * The open self-declared coordinate of a specialist's reasoning schema — any non-blank string is
 * valid. The specialist declares its own shape (used later by an {@link Assessment}); no enum, no
 * registry, no membership validation. The id is round-tripped verbatim through persistence,
 * AI-ready. This is the "schemaRef" property an assessment carries to say "I am structured this
 * way; a consumer who understands this schema can parse my details."
 */
public record SchemaRef(@JsonValue String id) {

  public SchemaRef {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("SchemaRef id cannot be null or blank");
    }
    id = id.trim();
  }

  @JsonCreator
  public static SchemaRef of(String id) {
    return new SchemaRef(id);
  }

  /**
   * Parses a string into a {@code SchemaRef}, returning {@code Optional.empty()} on null or blank.
   * Any non-blank trimmed string is a valid schema reference (open set — no membership check).
   */
  public static Optional<SchemaRef> parse(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(of(value));
  }
}
