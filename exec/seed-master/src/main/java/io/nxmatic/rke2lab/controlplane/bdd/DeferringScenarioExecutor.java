package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.impl.ScenarioExecutor;

/**
 * A {@link ScenarioExecutor} that plays a scenario but SKIPS every step body — the Java seam for
 * JGiven's dry-run when a scenario is driven programmatically rather than through the JUnit runner.
 *
 * <p>Why this exists: JGiven's dry-run (the {@code jgiven.report.dry-run} system property) is only
 * consulted inside {@link ScenarioExecutor#startScenario(Class, java.lang.reflect.Method,
 * java.util.List)} — the reflective JUnit entry point. Our checkpoints call the {@code
 * startScenario(String)} overload directly (they build the Given/When/Then by hand), and that path
 * never reads the property, so setting it has no effect: the step bodies run and a live probe hangs
 * during a {@code pulumi preview}. This subclass closes that gap by forcing {@code
 * methodInterceptor.disableMethodExecution()} — the exact switch the property path flips — right
 * after the scenario starts, so {@code getInvocationMode} returns {@code SKIPPED} and {@code
 * invoker.proceed()} is never called. The scenario is still PLAYED (its shell renders in the
 * runbook with every step marked skipped); only the bodies (the live probes) are suppressed.
 *
 * <p>{@code methodInterceptor} is {@code protected} on {@link ScenarioExecutor}, so this reaches it
 * by inheritance — no reflection, no {@code setAccessible}, verified at compile time and robust
 * under JPMS/JDK 25. A defer flag of {@code false} makes this a pass-through executor, so a caller
 * can construct it unconditionally and let the {@link io.nxmatic.rke2lab.pulumi.edge.LiveGate}
 * decide.
 */
public final class DeferringScenarioExecutor extends ScenarioExecutor {

  private final boolean defer;

  public DeferringScenarioExecutor(boolean defer) {
    this.defer = defer;
  }

  @Override
  public void startScenario(String description) {
    super.startScenario(description);
    if (defer) {
      methodInterceptor.disableMethodExecution();
    }
  }
}
