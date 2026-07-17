package io.nxmatic.rke2lab.incus.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.nxmatic.rke2lab.incus.contract.IncusRunbookInput;
import io.nxmatic.rke2lab.incus.contract.IncusRunbookInput.Worktree;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.Amendment;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.seed.broker.testkit.RefusingCellar;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the first link of the worktree wiring: the incus amend reflector binds the neutral {@code
 * worktree} role onto {@code IncusRunbookInput.worktree} — the host fills the flat provisioning
 * scalars without naming the field, and the scion reconstructs its topology from them. A pure-JSON
 * reflector, so no container is needed; the in-container proof of the scenario itself lives in
 * {@code IncusBddInContainerTest}.
 */
class IncusAmendReflectorTest {

  private static final SeedCodec CODEC = new SeedCodec();
  private final IncusAmendReflector reflector = new IncusAmendReflector();

  @Test
  void it_binds_the_worktree_role_onto_the_worktree_scalars() {
    final Worktree scalars =
        new Worktree("/srv/host/.local.d/worktree", "bioskop", "bioskop-master", true);
    final SeedEnvelope roleValues =
        new SeedEnvelope(
            "incus",
            "runbook",
            CODEC.encode(Map.of(Amendment.WORKTREE, CODEC.decode(CODEC.encode(scalars)))));

    final SeedEnvelope amended = reflector.handle(RefusingCellar.INSTANCE, roleValues);

    assertEquals("runbook", amended.coordinate(), "the amended payload is ready to sow at runbook");
    final IncusRunbookInput bound = CODEC.decode(amended.payload(), IncusRunbookInput.class);
    assertEquals(
        Optional.of(scalars), bound.worktree(), "the worktree role landed on the worktree scalars");
  }

  @Test
  void an_empty_amendment_keeps_the_default_empty_worktree() {
    final SeedEnvelope roleValues = new SeedEnvelope("incus", "runbook", CODEC.encode(Map.of()));

    final IncusRunbookInput bound =
        CODEC.decode(
            reflector.handle(RefusingCellar.INSTANCE, roleValues).payload(),
            IncusRunbookInput.class);

    assertEquals(
        Optional.empty(),
        bound.worktree(),
        "an unamended input keeps its empty worktree amendment");
  }

  @Test
  void it_rejects_a_coordinate_it_does_not_amend() {
    final SeedEnvelope unknown =
        new SeedEnvelope("incus", "not-a-bearer", CODEC.encode(Map.of(Amendment.WORKTREE, "/x")));

    assertThrows(
        IllegalArgumentException.class, () -> reflector.handle(RefusingCellar.INSTANCE, unknown));
  }

  @Test
  void it_serves_the_incus_amend_coordinate() {
    assertEquals(IncusScenario.PROVISION.amend(), reflector.serves());
  }
}
