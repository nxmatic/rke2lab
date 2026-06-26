package io.nxmatic.rke2lab.domain.annotations;

/**
 * The build-time staging laws the {@code staging-extension} enforces from bundle bytecode — the
 * rule a {@link GovernedBy} names so a package can set its reporting {@link EnforcementLevel} per
 * law.
 *
 * <ul>
 *   <li>{@link #RECORD_PURITY} — a {@code type=record} bundle may export only records, enums, and
 *       sealed ADT roots (a pure-data bundle, never behaviour).
 *   <li>{@link #SPEC_COVERAGE} — every exported type must be named in a {@code docs/} spec or carry
 *       {@link Transitional}; a type in neither is code-out-of-spec.
 *   <li>{@link #INSTANCE_DISCIPLINE} — exported types should not publish {@code public static}
 *       behaviour helpers (pass instances through the call graph; factories and constants exempt).
 * </ul>
 */
public enum Gate {
  RECORD_PURITY,
  SPEC_COVERAGE,
  INSTANCE_DISCIPLINE
}
