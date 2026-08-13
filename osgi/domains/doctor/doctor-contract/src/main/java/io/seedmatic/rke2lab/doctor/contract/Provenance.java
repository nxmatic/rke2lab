package io.seedmatic.rke2lab.doctor.contract;

import java.util.Optional;

/**
 * The source of an intervention — how the world came to change. Today the system records only its
 * own prescriptions ({@code pulumi-engine}); the operator's out-of-band fixes leave no trace, so
 * the efficacy calculation credits the wrong actor. This enum types the provenance so the medical
 * record can track who did what: the engine applied its own fix, the operator declared a manual
 * intervention, or drift was detected from an external change. The first step toward honest
 * attribution and stopping false efficacy credit.
 */
public enum Provenance {
  PULUMI_ENGINE("pulumi-engine"),
  OPERATOR_MANUAL("operator-manual"),
  EXTERNAL_CHANGE_DETECTED("external-change-detected");

  private final String id;

  Provenance(String id) {
    this.id = id;
  }

  /** The kebab-case id used in stack state and intervention records. */
  public String id() {
    return id;
  }

  public static Optional<Provenance> parse(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    final String normalized = value.trim().toLowerCase();
    for (Provenance provenance : values()) {
      if (provenance.id.equals(normalized) || provenance.name().equalsIgnoreCase(normalized)) {
        return Optional.of(provenance);
      }
    }
    return Optional.empty();
  }
}
