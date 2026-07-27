package io.nxmatic.rke2lab.incus.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.nxmatic.rke2lab.incus.contract.IncusRunbookInput;
import io.nxmatic.rke2lab.incus.contract.IncusRunbookInput.Facet;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.Amendment;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.seed.broker.testkit.RefusingCellar;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the first link of the facet wiring: the incus amend reflector binds the neutral {@code
 * facet} role onto {@code IncusRunbookInput.facet} — the host contributes the stable provisioning
 * identity without naming the field, and the scion reconstructs its topology from it (plus the root
 * it reads from the Worktree component). A pure-JSON reflector, so no container is needed; the
 * in-container proof of the scenario itself lives in {@code IncusBddInContainerTest}.
 */
class IncusAmendReflectorTest {

  private static final SeedCodec CODEC = new SeedCodec();
  private final IncusAmendReflector reflector = new IncusAmendReflector();

  @Test
  void it_binds_the_facet_role_onto_the_facet_scalars() {
    final Facet scalars = new Facet("bioskop", "bioskop-master", true, "/net/bioskop.local");
    final SeedEnvelope roleValues =
        new SeedEnvelope(
            "incus",
            "runbook",
            CODEC.encode(Map.of(Amendment.FACET, CODEC.decode(CODEC.encode(scalars)))));

    final SeedEnvelope amended = reflector.handle(RefusingCellar.INSTANCE, roleValues);

    assertEquals("runbook", amended.coordinate(), "the amended payload is ready to sow at runbook");
    final IncusRunbookInput bound = CODEC.decode(amended.payload(), IncusRunbookInput.class);
    assertEquals(Optional.of(scalars), bound.facet(), "the facet role landed on the facet scalars");
  }

  @Test
  void an_empty_amendment_keeps_the_default_empty_facet() {
    final SeedEnvelope roleValues = new SeedEnvelope("incus", "runbook", CODEC.encode(Map.of()));

    final IncusRunbookInput bound =
        CODEC.decode(
            reflector.handle(RefusingCellar.INSTANCE, roleValues).payload(),
            IncusRunbookInput.class);

    assertEquals(
        Optional.empty(), bound.facet(), "an unamended input keeps its empty facet amendment");
  }

  @Test
  void it_rejects_a_coordinate_it_does_not_amend() {
    final SeedEnvelope unknown =
        new SeedEnvelope("incus", "not-a-bearer", CODEC.encode(Map.of(Amendment.FACET, "/x")));

    assertThrows(
        IllegalArgumentException.class, () -> reflector.handle(RefusingCellar.INSTANCE, unknown));
  }

  @Test
  void it_serves_the_incus_amend_coordinate() {
    assertEquals(IncusScenario.PROVISION.amend(), reflector.serves());
  }
}
