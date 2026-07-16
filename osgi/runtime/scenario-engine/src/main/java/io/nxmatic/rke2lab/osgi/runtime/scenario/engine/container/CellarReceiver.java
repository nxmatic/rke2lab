package io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container;

import io.nxmatic.rke2lab.seed.broker.port.Cellar;

/**
 * A scenario that RECEIVES its transactional {@link Cellar} from the {@code
 * ScenarioCellarExtension} — the injection hook, the twin of {@code SeedReceiver}. The extension
 * builds the run's {@link ScenarioCellar} and hands it here (a field set) before the body runs, so
 * the scenario stores through the ONE universal cellar rather than resolving one from the registry.
 *
 * <p>Opt-in by implementing this: a scenario that does not receive a cellar is left untouched.
 */
@FunctionalInterface
public interface CellarReceiver {

  /** Receive the run's transactional cellar, before the test body runs. */
  void receiveCellar(Cellar cellar);
}
