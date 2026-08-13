package io.seedmatic.rke2lab.seed.broker.port;

import java.util.Map;

/**
 * Gathers the AMBIENT amendments for a coordinate: give it an {@link AmendCoordinate} and it
 * returns the merged {@code {role → value}} every {@link AmendmentContributor} for that coordinate
 * holds. The declarative sibling of the {@link SeedBroker} — the broker routes a coordinate to the
 * one handler that {@code serves} it; the assembler collects every contributor that FILLS one, with
 * the same {@code @Reference(MULTIPLE)} shape and the same single responsibility split (the broker
 * keeps routing, the assembler keeps gathering). A domain's amend reflector consults it at the door
 * and merges the ambient roles under the roles a sower already offered, so no sower holds a role it
 * does not own.
 *
 * <p>Values are serialized JSON {@code String}s (the seam rule — see {@link AmendmentContributor});
 * the assembler only carries them, the reflector decodes each with its own codec. Two contributors
 * claiming one role for one coordinate is a wiring bug the assembler fails loudly on, never
 * silently picks one — the same discipline the broker holds for a doubly-served coordinate. See
 * docs/architecture/osgi/seed-broker-spec.adoc (§ @Amendment).
 */
public interface AmendmentAssembler {

  /**
   * The merged {@code {role → value}} of every {@link AmendmentContributor} whose {@link
   * AmendmentContributor#coordinate} equals {@code coordinate}. Empty when none contributes — the
   * coordinate then carries only what the sower offered (or its defaults).
   */
  Map<String, String> gather(AmendCoordinate coordinate);
}
