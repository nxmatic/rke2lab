package io.nxmatic.rke2lab.maven.staging;

/**
 * The staging extension's own view of {@code io.nxmatic.rke2lab.domain.annotations.StagingGate} —
 * the build-time staging laws, each governable to its own {@link EnforcementLevel} per package.
 * Read from the {@code @GovernedBy} annotation's enum {@code value} via ASM (the extension cannot
 * link the annotation module; see {@link EnforcementLevel}). The constant NAMES must stay in step
 * with the annotation module's enum.
 */
enum StagingGate {
  CONTRACT_PURITY,
  SPEC_COVERAGE,
  INSTANCE_DISCIPLINE,
  REALM_BOUNDARY,
  DUPLICATE_REALM_CLASS,
  REALM_WIRING_INTEGRITY,
  SYNTHESIS_PATTERN;

  /** Map an ASM enum-constant name to a gate, or {@code null} for an unknown name (ignored). */
  static StagingGate fromName(String name) {
    for (StagingGate gate : values()) {
      if (gate.name().equals(name)) {
        return gate;
      }
    }
    return null;
  }
}
