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
 *   <li>{@link #REALM_BOUNDARY} — no class references a type unreachable in its classloader realm
 *       (flat vs bundle realms; host/seam leak and OSGi-internal leak).
 *   <li>{@link #DUPLICATE_REALM_CLASS} — no package's classes live in BOTH realms at once: a
 *       package a staged bundle exports must not ALSO be present flat in the assembled host. Two
 *       copies of a class — one loaded flat, one by a bundle classloader — is the loader-constraint
 *       collision that surfaces as a {@code LinkageError} when an instance crosses the seam.
 *   <li>{@link #PIPELINE_PATTERN} — a synthesis topic follows the documented shape: it {@code
 *       implements Topic.Execution} and pushes its output through its {@code Sink}. The read-face
 *       invariant (a topic reads a produced slot through a {@code Supplier}, never a copied
 *       reference) is a later increment of this gate. See
 *       docs/architecture/patterns/fluent-pipeline-grammar.adoc.
 * </ul>
 */
public enum StagingGate {
  RECORD_PURITY,
  SPEC_COVERAGE,
  INSTANCE_DISCIPLINE,
  REALM_BOUNDARY,
  DUPLICATE_REALM_CLASS,
  PIPELINE_PATTERN
}
