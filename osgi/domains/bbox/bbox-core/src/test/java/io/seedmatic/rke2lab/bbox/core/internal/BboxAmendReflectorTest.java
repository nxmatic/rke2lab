package io.seedmatic.rke2lab.bbox.core.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.seedmatic.rke2lab.bbox.contract.BboxCoordinate;
import io.seedmatic.rke2lab.bbox.contract.BboxRunbookInput;
import io.seedmatic.rke2lab.bbox.contract.BboxRunbookInput.Router;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.Amendment;
import io.seedmatic.rke2lab.seed.broker.port.AmendmentAssembler;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.seedmatic.rke2lab.seed.broker.testkit.RefusingCellar;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the central link of the bbox secret wiring: the amend reflector binds the neutral {@code
 * FACET} role — the {@code {uri, password}} router contact the host joined from {@code
 * .secrets:lan.bbox} — onto {@code BboxRunbookInput.router}, so the live secret reaches the scion
 * instead of a hardcoded marker. The host sows this FACET as the bbox crossing's one per-consult
 * amendment; the reflector must {@code gather} it and bind it onto the input's defaults. A
 * pure-JSON reflector, so no container is needed; the in-container proof of the scenario itself
 * lives in {@code BboxBddInContainerTest}. Mirrors {@code IncusAmendReflectorTest}.
 */
class BboxAmendReflectorTest {

  private static final SeedCodec CODEC = new SeedCodec();

  /** The per-consult trigger the host sows the bbox amend with, keyed at the runbook contract. */
  private static SeedEnvelope trigger(String coordinate) {
    return new SeedEnvelope("bbox", coordinate, CODEC.encode(Map.of()));
  }

  /** An assembler that gathers the given ambient roles for any coordinate. */
  private static AmendmentAssembler ambient(Map<String, String> roles) {
    return coordinate -> roles;
  }

  @Test
  void it_binds_the_facet_role_onto_the_router_contact() {
    final Router router = new Router("https://mabbox.bytel.fr", Optional.of("s3cr3t"));
    final BboxAmendReflector reflector =
        new BboxAmendReflector(ambient(Map.of(Amendment.FACET, CODEC.encode(router))));

    final SeedEnvelope amended = reflector.handle(RefusingCellar.INSTANCE, trigger("runbook"));

    assertEquals("runbook", amended.coordinate(), "the amended payload is ready to sow at runbook");
    final BboxRunbookInput bound = CODEC.decode(amended.payload(), BboxRunbookInput.class);
    assertEquals(router, bound.router(), "the FACET role landed on the router contact");
  }

  @Test
  void an_unamended_input_fails_because_the_router_facet_is_mandatory() {
    final BboxAmendReflector reflector = new BboxAmendReflector(ambient(Map.of()));

    // BboxRunbookInput.router is a non-Optional FACET — the door supplies no default, so a sower
    // that offers no FACET fails loud rather than silently getting the public-uri marker.
    assertThrows(
        IllegalArgumentException.class,
        () -> reflector.handle(RefusingCellar.INSTANCE, trigger("runbook")));
  }

  @Test
  void it_rejects_a_coordinate_it_does_not_amend() {
    final BboxAmendReflector reflector = new BboxAmendReflector(ambient(Map.of()));

    assertThrows(
        IllegalArgumentException.class,
        () -> reflector.handle(RefusingCellar.INSTANCE, trigger("not-a-bearer")));
  }

  @Test
  void it_serves_the_bbox_amend_coordinate() {
    final BboxAmendReflector reflector = new BboxAmendReflector(ambient(Map.of()));
    assertEquals(BboxCoordinate.AMEND, reflector.serves());
  }
}
