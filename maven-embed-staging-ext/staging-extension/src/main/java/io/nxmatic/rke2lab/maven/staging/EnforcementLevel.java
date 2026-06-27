package io.nxmatic.rke2lab.maven.staging;

/**
 * The staging extension's own view of {@code
 * io.nxmatic.rke2lab.domain.annotations.EnforcementLevel} — how a {@link StagingGate} reports the
 * violations it finds. The extension is installed BEFORE the reactor that builds {@code
 * domain-annotations}, so it cannot link that enum; it reads the annotation's value as an ASM
 * enum-constant name and maps it here, exactly as it reads bnd header strings into its own {@link
 * io.nxmatic.rke2lab.osgi.bnd.EmbedCapability} view. The constant NAMES must stay in step with the
 * annotation module's enum.
 */
enum EnforcementLevel {
  IGNORE,
  WARN,
  ERROR;

  /** Map an ASM enum-constant name to a level, defaulting to {@link #ERROR} for an unknown name. */
  static EnforcementLevel fromName(String name) {
    for (EnforcementLevel level : values()) {
      if (level.name().equals(name)) {
        return level;
      }
    }
    return ERROR;
  }
}
