package io.nxmatic.rke2lab.seed.bdd;

/**
 * A root scenario that RECEIVES one bootstrap seed from the driver — the irreducible fact only the
 * driver can capture (e.g. the {@code RunMode}, knowable only inside {@code Pulumi.run}). The
 * driver seeds the value into the launcher's session store; {@link SessionSeed} (a post-processor)
 * reads it and hands it here before the GIVEN runs, so the scenario bootstraps from it.
 *
 * <p>This is the inbound channel, minus the machinery: one value set on the scenario, no executor
 * swap, no model injection, no probe fan-out (what the former {@code HostSeeder} conflated). See
 * docs/architecture/osgi/seed-bdd-module-spec.adoc (§ the amorce).
 *
 * @param <T> the seeded fact's type — concrete in the exec (e.g. {@code RunMode}, host-only), never
 *     named by this foundation seam
 */
@FunctionalInterface
public interface SeedReceiver<T> {

  /** Receive the driver's bootstrap seed, before the GIVEN runs. */
  void receiveSeed(T seed);
}
