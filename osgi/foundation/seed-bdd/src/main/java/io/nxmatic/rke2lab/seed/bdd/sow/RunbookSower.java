package io.nxmatic.rke2lab.seed.bdd.sow;

import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedBroker;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;

/**
 * The SOW half of the sow-and-graft crossing, on its own — REALM-AGNOSTIC by design. It sows a
 * soil's {@link RunbookCoordinate} through the {@link SeedBroker} door and reaps the runbook JSON
 * (the {@code runbook} field of the domain-sealed {@code RunbookEnvelope}, read GENERICALLY — never
 * a domain wire-record). Nothing here is host-specific: it holds only the neutral broker seam and a
 * codec, so the SAME sower serves the host today (embedded, one host sows) and a scion tomorrow
 * (remote, a peer sows another peer's runbook through the same door, in-container).
 *
 * <p>This is why it lives in its OWN package, exported by the bnd: the GRAFT half ({@code
 * ScenarioGraft}) is local to whoever owns the runbook tree and stays host-flat, but the sow is the
 * portable gesture the broker was built to make realm-indifferent (§ REST — embedded today, remote
 * tomorrow, same contract). See docs/architecture/osgi/seed-bdd-module-spec.adoc.
 */
public final class RunbookSower {

  private final SeedBroker broker;
  private final SeedCodec codec = new SeedCodec();

  public RunbookSower(SeedBroker broker) {
    this.broker = broker;
  }

  /**
   * Sow {@code soil}'s runbook coordinate and reap its runbook JSON. The trigger envelope is empty:
   * the coordinate carries the whole request (which soil to play), and the scion resolves its own
   * collaborators in-container. The reaped payload is the serialized {@code RunbookEnvelope}; only
   * its {@code runbook} field is pulled, so no domain type is named.
   */
  public String sowRunbook(String soil) {
    final RunbookCoordinate coordinate = new RunbookCoordinate(soil);
    final SeedEnvelope reaped = broker.sow(coordinate, SeedEnvelope.of(coordinate, "{}"));
    return codec.decode(reaped.payload()).path("runbook").asText();
  }
}
