package io.nxmatic.rke2lab.seed.bdd.sow;

import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.AmendCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedBroker;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.Map;

/**
 * The open gardening — the framework as the gardener works it. An INSTANCE holding what it opened
 * (the live {@link OsgiConnection}) and the gardener it found there (the {@link SeedBroker}, "the
 * gardener realised as the SeedBroker"), and it knows how to SOW: hand it a soil name and it grows
 * that soil's scenario through the gardener, reaping the runbook. The driver {@link #open}s it
 * before playing, hands it to the scenario's GIVEN ("I have access to the open gardening"), and
 * {@link #close}s it after — so the gardening's whole lifecycle is one instance passed through the
 * call graph, never a static helper.
 *
 * <p>REALM-AGNOSTIC by design (why it lives in the exported {@code seed.bdd.sow} package): the
 * driver opens it host-side today (embedded), but a scion-peer could hold an open gardening
 * in-container tomorrow (remote) and sow another peer's runbook through the same door — the sowing
 * gesture is what the broker was built to make realm-indifferent. See
 * docs/architecture/osgi/seed-broker-spec.adoc and the gardening lexicon.
 */
public record Gardening(OsgiConnection connection, SeedBroker gardener) implements AutoCloseable {

  /** SCR publishes the gardener only after its handlers bind; wait a bounded while for it. */
  private static final long GARDENER_TIMEOUT_MILLIS = 30_000;

  /**
   * Open the gardening: boot the framework (embedded, from the staged bundles the exec-jar carries)
   * and find the gardener in it. The connection owns the framework's lifecycle, so {@link #close}
   * stops the world.
   */
  public static Gardening open() {
    final OsgiConnection connection = OsgiConnection.embedded();
    final SeedBroker gardener = connection.awaitService(SeedBroker.class, GARDENER_TIMEOUT_MILLIS);
    if (gardener == null) {
      throw new IllegalStateException(
          "no gardener in the open gardening within "
              + GARDENER_TIMEOUT_MILLIS
              + "ms — the broker runtime bundle (seed-broker-runtime) is not staged, or its "
              + "handlers never bound");
    }
    return new Gardening(connection, gardener);
  }

  /**
   * Sow {@code soil}'s runbook coordinate through the gardener and reap its runbook JSON. The
   * trigger envelope is empty: the coordinate carries the whole request (which soil to play), and
   * the scion resolves its own collaborators in-container. Only the {@code runbook} field of the
   * reaped {@code RunbookEnvelope} is pulled — read GENERICALLY, never a domain wire-record.
   */
  public String sow(String soil) {
    return sow(soil, Map.of());
  }

  /**
   * Sow {@code soil} with {@code amendments} — a {@code {role → value}} map the host holds under
   * NEUTRAL {@link io.nxmatic.rke2lab.seed.broker.port.Amendment} roles (e.g. {@code soil} → the
   * plot to materialise into). When non-empty, a first sow at {@link AmendCoordinate} reconciles
   * the roles onto the target's runbook input at the DOOR — the host names no domain field — and
   * the reconciled payload feeds the runbook sow; when empty, the runbook is sown with the empty
   * trigger (the scion falls back to its own defaults). Only the {@code runbook} field of the
   * reaped envelope is pulled, read generically.
   */
  public String sow(String soil, Map<String, String> amendments) {
    final RunbookCoordinate coordinate = new RunbookCoordinate(soil);
    final SeedCodec codec = new SeedCodec();
    final SeedEnvelope trigger =
        amendments.isEmpty()
            ? SeedEnvelope.of(coordinate, "{}")
            : gardener.sow(
                new AmendCoordinate(soil),
                new SeedEnvelope(soil, coordinate.slug(), codec.encode(amendments)));
    final SeedEnvelope reaped = gardener.sow(coordinate, trigger);
    return codec.decode(reaped.payload()).path("runbook").asText();
  }

  /** Close the gardening — stop the framework the connection owns. */
  @Override
  public void close() {
    connection.close();
  }
}
