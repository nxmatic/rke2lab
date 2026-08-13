package io.seedmatic.rke2lab.seed.broker.port;

import java.util.Map;

/**
 * A role-owner's standing contribution to an {@link AmendCoordinate}'s fill: "for this domain's
 * amend input, here are the {@code {role → value}} I hold". The declarative twin of an imperative
 * amend sow — a sower ({@code incus}) sows the PER-CONSULT roles it derives in-world ({@code SOIL},
 * {@code WORKTREE}); a contributor publishes the AMBIENT roles a standing owner holds ({@code
 * FACET}, the operator config the host read once). Both meet at the amend door, where the {@link
 * AmendmentAssembler} gathers the contributions and the domain's amend reflector merges them under
 * the sown payload before binding — so no sower needs to hold a role it does not own.
 *
 * <p>Contribution-without-acquaintance, the codebase idiom: an owner declares only its {@link
 * #coordinate} and its roles; the assembler {@code @Reference(MULTIPLE)}s every contributor and
 * knows none of them. OSGi-native owners publish as {@code @Component}; a host-side owner registers
 * the same service through the framework {@code BundleContext} it already holds (the door the root
 * uses for {@link RunGate}, {@link OpaqueCellar}, {@link Parcel}). Dual-realm, so it lives here in
 * the neutral port both worlds see.
 *
 * <p>Jackson-free by the seam rule: a {@link #roles} value is a serialized JSON {@code String} (the
 * owner's own jackson did the encode), never a {@code JsonNode} — a node payload once leaked the
 * jackson bundle across the flat seam and threw a {@code LinkageError}. The assembler carries the
 * Strings; the reflector decodes each with ITS own codec. See
 * docs/architecture/osgi/seed-broker-spec.adoc (§ @Amendment).
 */
public interface AmendmentContributor {

  /** The amend coordinate whose input this fills — the domain the roles belong to. */
  AmendCoordinate coordinate();

  /**
   * The {@code {role → value}} this owner holds for {@link #coordinate}, each value a serialized
   * JSON {@code String}. Keyed by the neutral {@link Amendment} roles; a partial map is legal (fill
   * only what you own). Empty when the owner holds nothing this run — the coordinate then keeps its
   * defaults for those roles.
   */
  Map<String, String> roles();
}
