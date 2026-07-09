package io.nxmatic.rke2lab.doctor.records;

import io.nxmatic.rke2lab.seed.broker.port.WireEnum;
import java.util.Optional;

/**
 * The closed set of provisioning actions a {@code readiness-verdict} {@code SeedEnvelope} carries.
 * The host must not invent an action — this enum is the authority. {@code slug()} is the wire value
 * placed in the verdict's {@code action} field; call sites reference this, never literals.
 */
public enum Action implements WireEnum {
  STOP("stop"),
  CONTINUE_DEGRADED("continue-degraded");

  private final String slug;

  Action(String slug) {
    this.slug = slug;
  }

  @Override
  public String slug() {
    return slug;
  }

  /** Resolves a wire action; null/blank/unknown yields empty. */
  public static Optional<Action> parse(String slug) {
    if (slug == null || slug.isBlank()) {
      return Optional.empty();
    }
    for (Action action : values()) {
      if (action.slug.equals(slug)) {
        return Optional.of(action);
      }
    }
    return Optional.empty();
  }
}
