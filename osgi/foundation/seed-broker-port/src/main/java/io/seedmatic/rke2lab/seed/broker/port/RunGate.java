package io.seedmatic.rke2lab.seed.broker.port;

import java.util.function.Supplier;

/**
 * The ambient run-condition, published ONCE as a service the whole run shares: are we cultivating
 * (a live run that touches the real world — kubectl, dbus, the Pulumi backend) or only surveying (a
 * preview/dry-run that plans without touching)? It is a fact of the WHOLE run, not a property of
 * any one seed, so it is an ambient service, not a value carried on an envelope.
 *
 * <p>Consumed by the SCION: a domain scenario resolves the gate from its own {@code BundleContext}
 * alongside its contact and the doctor, and derives its touch decision from it — when the gate is
 * closed it asks the edge for the inert crossing (a dry-run, a synthetic observation), so the REAL
 * edge does nothing, never a fabricated fake. Resolution is by service lookup (the collaborators
 * are looked up, never sown), which is why the gate lives in the registry, here in the neutral seam
 * both worlds see, rather than riding a seed. The host publishes the value at boot (projected from
 * its {@code RunMode}); no {@code com.pulumi} type crosses — the scion consumes only this seam.
 *
 * <p>A mechanism, not an orchestrator: it answers {@link #cultivating()} and offers one combinator
 * so the scion expresses "touch, or stay home" without branching on a bare boolean. See
 * docs/architecture/osgi/seed-broker-spec.adoc (the RunGate section) and the gardening lexicon
 * (cultiver / arpenter).
 */
public interface RunGate {

  /**
   * Whether we cultivate (a live run touches the world) rather than survey (a preview plans only).
   */
  boolean cultivating();

  /**
   * Cross the boundary: run {@code touch} when cultivating (live), {@code inert} when surveying
   * (preview). The scion supplies both branches; the gate chooses. Neither branch is evaluated
   * until chosen.
   */
  default <T> T through(Supplier<T> touch, Supplier<T> inert) {
    return cultivating() ? touch.get() : inert.get();
  }
}
