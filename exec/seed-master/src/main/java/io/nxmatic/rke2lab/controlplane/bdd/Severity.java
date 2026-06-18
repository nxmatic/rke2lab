package io.nxmatic.rke2lab.controlplane.bdd;

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
  public static java.util.Optional<Severity> parse(String value) {
    if (value == null || value.isBlank()) {
      return java.util.Optional.empty();
    }
    return switch (value.trim().toLowerCase()) {
      case "critical", "crit", "stop" -> java.util.Optional.of(CRITICAL);
      case "warning", "warn", "degraded" -> java.util.Optional.of(WARNING);
      default -> java.util.Optional.empty();
    };
  }
}
