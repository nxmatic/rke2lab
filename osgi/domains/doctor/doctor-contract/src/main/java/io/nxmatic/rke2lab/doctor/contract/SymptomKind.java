package io.nxmatic.rke2lab.doctor.contract;

import io.nxmatic.rke2lab.seed.broker.port.WireEnum;
import java.util.Optional;

/**
 * The closed set of symptom kinds the host may report in a {@code readiness-checkpoint} {@code
 * SeedEnvelope}. This is the host-flat twin of the doctor's internal {@code
 * io.nxmatic.rke2lab.doctor.contract.Symptom} enum — OSGi owns the real {@code Symptom} and maps
 * {@code SymptomKind}→{@code Symptom} internally (the seed-broker must not depend on
 * doctor-records). {@code slug()} is the wire value placed in the checkpoint's {@code symptomKind}
 * field.
 */
public enum SymptomKind implements WireEnum {
  CONNECTION_REFUSED("connection-refused"),
  TIMEOUT("timeout"),
  KUBECONFIG_MISSING("kubeconfig-missing"),
  API_NOT_READY("api-not-ready"),
  CONTROLLER_NOT_READY("controller-not-ready"),
  RESERVATION_REFUSED("reservation-refused");

  private final String slug;

  SymptomKind(String slug) {
    this.slug = slug;
  }

  @Override
  public String slug() {
    return slug;
  }

  /** Resolves a wire symptom kind; null/blank/unknown yields empty. */
  public static Optional<SymptomKind> parse(String slug) {
    if (slug == null || slug.isBlank()) {
      return Optional.empty();
    }
    for (SymptomKind kind : values()) {
      if (kind.slug.equals(slug)) {
        return Optional.of(kind);
      }
    }
    return Optional.empty();
  }
}
