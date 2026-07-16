package io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container;

import io.nxmatic.rke2lab.seed.broker.port.Cellar;

/**
 * A scenario that RECEIVES its transactional cellar from the {@code ScenarioCellarExtension} — the
 * injection hook, the twin of {@code SeedReceiver}. The extension builds the run's {@link
 * ScenarioCellar} and hands it here (a field set) before the body runs, so the scenario stores
 * through the ONE universal cellar rather than resolving one from the registry.
 *
 * <p>Generic over the received type ({@code SeedReceiver}'s pattern): a scenario that only stores
 * declares {@code CellarReceiver<Cellar>} (the neutral verbs suffice); a scenario that also sows a
 * SUB-scion declares {@code CellarReceiver<ScenarioCellar>} to read {@code transactionId()} and
 * pass it on. The extension always injects a {@link ScenarioCellar}, so both bindings are
 * satisfied.
 *
 * <p>Opt-in by implementing this: a scenario that does not receive a cellar is left untouched.
 *
 * @param <C> the cellar face the scenario needs — {@link Cellar} (store-only) or {@link
 *     ScenarioCellar} (also {@code transactionId()})
 */
@FunctionalInterface
public interface CellarReceiver<C extends Cellar> {

  /** Receive the run's transactional cellar, before the test body runs. */
  void receiveCellar(C cellar);
}
