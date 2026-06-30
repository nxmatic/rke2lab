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
 *   <li>{@link #SCHEMA_CONCORD} — each Document coordinate has a JSON Schema ({@code
 *       doctor-core/.../schema/<slug>.schema.json}) that (a) is itself valid against the
 *       JSON-Schema meta-schema and (b) declares exactly the {@code WorldGatewayCatalog.FIELD_*}
 *       properties the coordinate's producer/consumer code reads and writes. A field written but
 *       not in the schema, or required by the schema but never written, is a concord violation.
 * </ul>
 */
public enum StagingGate {
  RECORD_PURITY,
  SPEC_COVERAGE,
  INSTANCE_DISCIPLINE,
  REALM_BOUNDARY,
  DUPLICATE_REALM_CLASS,
  SCHEMA_CONCORD
}
