package io.nxmatic.rke2lab.seed.broker.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.seed.broker.port.AmendCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.AmendmentContributor;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.seed.broker.port.SeedHandler;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the assembler's two guarantees: it MERGES the ambient roles of the contributors that fill a
 * coordinate, and it fails LOUD both ways — two contributors claiming one role (ambiguity) and
 * BETA, a contributor published for a coordinate no grower serves (the one silent GATHER seam, made
 * loud).
 */
class DefaultAmendmentAssemblerTest {

  private static final AmendCoordinate MANIFESTS = new AmendCoordinate("manifests");
  private static final AmendCoordinate INCUS = new AmendCoordinate("incus");

  private static SeedHandler grower(SeedCoordinate serves) {
    return new SeedHandler() {
      @Override
      public SeedCoordinate serves() {
        return serves;
      }

      @Override
      public SeedEnvelope handle(Cellar cellar, SeedEnvelope seed) {
        throw new UnsupportedOperationException("a grower stub — only serves() is exercised");
      }
    };
  }

  private record Contributor(AmendCoordinate coordinate, Map<String, String> roles)
      implements AmendmentContributor {}

  private static DefaultAmendmentAssembler assembler(
      List<AmendmentContributor> contributors, List<SeedHandler> growers) {
    // The test constructor supplies the grower roster directly (production derefs OSGi
    // ServiceReferences lazily at gather; the test needs no OSGi). Contributors bind on the field.
    final DefaultAmendmentAssembler assembler = new DefaultAmendmentAssembler(growers);
    assembler.contributors = contributors;
    return assembler;
  }

  @Test
  void gathers_only_the_roles_of_contributors_for_the_wanted_coordinate() {
    final DefaultAmendmentAssembler assembler =
        assembler(
            List.of(
                new Contributor(MANIFESTS, Map.of("facet", "{\"mesh\":false}")),
                new Contributor(INCUS, Map.of("image", "{\"alias\":\"node\"}"))),
            List.of(grower(MANIFESTS), grower(INCUS)));

    assertEquals(Map.of("facet", "{\"mesh\":false}"), assembler.gather(MANIFESTS));
  }

  @Test
  void gathers_empty_when_no_contributor_fills_the_coordinate() {
    final DefaultAmendmentAssembler assembler = assembler(List.of(), List.of(grower(MANIFESTS)));

    assertTrue(assembler.gather(MANIFESTS).isEmpty());
  }

  @Test
  void fails_loud_when_two_contributors_claim_one_role() {
    final DefaultAmendmentAssembler assembler =
        assembler(
            List.of(
                new Contributor(MANIFESTS, Map.of("facet", "a")),
                new Contributor(MANIFESTS, Map.of("facet", "b"))),
            List.of(grower(MANIFESTS)));

    final IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> assembler.gather(MANIFESTS));
    assertTrue(ex.getMessage().contains("ambiguous"), ex.getMessage());
  }

  @Test
  void beta_fails_loud_on_an_orphan_contributor_no_grower_serves() {
    // The silent seam: a contributor whose coordinate diverges from every grower's (a slug typo).
    final DefaultAmendmentAssembler assembler =
        assembler(
            List.of(new Contributor(new AmendCoordinate("manifezts"), Map.of("facet", "x"))),
            List.of(grower(MANIFESTS)));

    final IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> assembler.gather(MANIFESTS));
    assertTrue(ex.getMessage().contains("no grower serves"), ex.getMessage());
  }

  @Test
  void beta_accepts_a_contributor_whose_coordinate_a_grower_serves() {
    final DefaultAmendmentAssembler assembler =
        assembler(
            List.of(new Contributor(MANIFESTS, Map.of("facet", "x"))), List.of(grower(MANIFESTS)));

    assertEquals(Map.of("facet", "x"), assembler.gather(MANIFESTS));
  }
}
