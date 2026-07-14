package io.nxmatic.rke2lab.incus.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.nxmatic.rke2lab.incus.contract.IncusRunbookInput;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.AmendCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.Amendment;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the first link of the SOIL wiring (I2): the incus amend reflector binds the neutral {@code
 * soil} role onto {@code IncusRunbookInput.materializationRoot} — the host fills the plot without
 * naming the field. A pure-JSON reflector, so no container is needed; the in-container proof of the
 * scenario itself lives in {@code IncusBddInContainerTest}.
 */
class IncusAmendReflectorTest {

  private static final SeedCodec CODEC = new SeedCodec();
  private final IncusAmendReflector reflector = new IncusAmendReflector();

  @Test
  void it_binds_the_soil_role_onto_the_materialization_root() {
    final String plot = "/srv/host/.local.d/bioskop/bioskop-master/host.staging.1/rke2-manifests.d";
    final SeedEnvelope roleValues =
        new SeedEnvelope("incus", "runbook", CODEC.encode(Map.of(Amendment.SOIL, plot)));

    final SeedEnvelope amended = reflector.handle(roleValues);

    assertEquals("runbook", amended.coordinate(), "the amended payload is ready to sow at runbook");
    final IncusRunbookInput bound = CODEC.decode(amended.payload(), IncusRunbookInput.class);
    assertEquals(plot, bound.materializationRoot(), "the soil role landed on materializationRoot");
  }

  @Test
  void an_empty_amendment_keeps_the_default_blank_soil() {
    final SeedEnvelope roleValues = new SeedEnvelope("incus", "runbook", CODEC.encode(Map.of()));

    final IncusRunbookInput bound =
        CODEC.decode(reflector.handle(roleValues).payload(), IncusRunbookInput.class);

    assertEquals(
        IncusRunbookInput.defaults().materializationRoot(),
        bound.materializationRoot(),
        "an unamended input keeps its blank soil");
  }

  @Test
  void it_rejects_a_coordinate_it_does_not_amend() {
    final SeedEnvelope unknown =
        new SeedEnvelope("incus", "not-a-bearer", CODEC.encode(Map.of(Amendment.SOIL, "/x")));

    assertThrows(IllegalArgumentException.class, () -> reflector.handle(unknown));
  }

  @Test
  void it_serves_the_incus_amend_coordinate() {
    assertEquals(new AmendCoordinate("incus"), reflector.serves());
  }
}
