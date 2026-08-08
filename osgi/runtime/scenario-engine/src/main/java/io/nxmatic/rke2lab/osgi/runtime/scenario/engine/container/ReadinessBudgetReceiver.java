package io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container;

import io.nxmatic.rke2lab.osgi.runtime.readiness.ReadinessBudget;

/**
 * A readiness scenario that RECEIVES its resolved {@link ReadinessBudget} before the body — the
 * readiness twin of {@link InputReceiver} / {@link CellarReceiver}. The {@link
 * ReadinessBudgetExtension} reads the {@code @ReadinessDeadlines} defaults off the {@code @Test},
 * folds the stack override over them, and hands the effective budget here; the scenario then
 * threads it into its capture step, where the edge's {@code awaitReady} bounds the reach +
 * convergence.
 *
 * <p>Opt-in by implementing it — a scenario with no readiness checkpoint does not. Implementing it
 * REQUIRES a {@code @ReadinessDeadlines} on the {@code @Test}: the extension fails loud otherwise,
 * so the patience is never silently defaulted.
 */
@FunctionalInterface
public interface ReadinessBudgetReceiver {

  /** Receive the effective readiness budget, before the test body runs. */
  void receiveBudget(ReadinessBudget budget);
}
