package io.seedmatic.rke2lab.domain.annotations;

/**
 * The build-time staging laws the {@code staging-extension} enforces from bundle bytecode — the
 * rule a {@link GovernedBy} names so a package can set its reporting {@link EnforcementLevel} per
 * law.
 *
 * <ul>
 *   <li>{@link #CONTRACT_PURITY} — a {@code type=contract} bundle may export records, enums, sealed
 *       ADT roots, AND service interfaces, but NO concrete class (a domain's data vocabulary +
 *       consumer-side contract, never an implementation — the "no impl" guard that keeps the
 *       SCR-requiring live impl in the domain's {@code -core}). Widened from the former {@code
 *       RECORD_PURITY} (records/enums/sealed only): a contract is a record bundle that may also
 *       carry the interfaces a consumer resolves, so the two categories fused into one.
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
 *   <li>{@link #REALM_WIRING_INTEGRITY} — the assembled uber-jar actually BOOTS: an embedded
 *       framework over the exec's own {@code -exec.jar} resolves every staged bundle, and the flat
 *       (system-bundle) and installed-bundle export-sets stay disjoint. Observed against the real
 *       resolver, so it catches an unsatisfied import, an unattachable fragment, or a split package
 *       that a static manifest scan cannot — the embedded-boot smoke test. Orthogonal to {@link
 *       #DUPLICATE_REALM_CLASS}: it proves the assembly is wireable, not that no class is
 *       duplicated.
 *   <li>{@link #SYNTHESIS_PATTERN} — a manifests synthesis phase follows the documented shape: it
 *       {@code implements Phase.Execution} and pushes its output through its {@code Sink}. The
 *       read-face invariant (a phase reads a produced slot through a {@code Supplier}, never a
 *       copied reference) is a later increment of this gate.
 * </ul>
 */
public enum StagingGate {
  CONTRACT_PURITY,
  SPEC_COVERAGE,
  INSTANCE_DISCIPLINE,
  REALM_BOUNDARY,
  DUPLICATE_REALM_CLASS,
  REALM_WIRING_INTEGRITY,
  SYNTHESIS_PATTERN
}
