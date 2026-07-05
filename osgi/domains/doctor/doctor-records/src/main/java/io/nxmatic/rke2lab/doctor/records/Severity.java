package io.nxmatic.rke2lab.doctor.records;

import java.util.Optional;

/**
 * How a failed readiness scenario affects provisioning. A scenario declares its intrinsic severity
 * from domain knowledge; the operator can override it per scenario (see {@code
 * ControlplanePolicy.readiness}).
 */
public enum Severity {
  /** Failure stops provisioning — nothing downstream can proceed. */
  CRITICAL,
  /** Failure is tolerated; provisioning continues in a degraded mode. */
  WARNING;

  /** Parses an operator override value ("critical"/"warning"); blank/unknown yields empty. */
  public static Optional<Severity> parse(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    return switch (value.trim().toLowerCase()) {
      case "critical", "crit", "stop" -> Optional.of(CRITICAL);
      case "warning", "warn", "degraded" -> Optional.of(WARNING);
      default -> Optional.empty();
    };
  }
}
