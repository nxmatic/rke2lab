package io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container;

import io.nxmatic.rke2lab.osgi.runtime.readiness.ReadinessBudget;
import io.nxmatic.rke2lab.seed.broker.port.ReadinessDeadlineOverride;
import io.nxmatic.rke2lab.seed.broker.port.ReadinessOverrides;
import java.time.Duration;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.osgi.framework.FrameworkUtil;

/**
 * Resolves a readiness scenario's effective {@link ReadinessBudget} and hands it to the scenario
 * before the body — the readiness twin of {@link ScenarioInputSeed}, part of the
 * {@code @SeedScenario} socle. It folds two sources per checkpoint: the {@link ReadinessDeadlines}
 * the {@code @Test} DECLARES (the visible-in-code defaults) and the stack {@link
 * ReadinessOverrides} the host published as an ambient service — {@code effective =
 * override.orElse(annotation-default)} per deadline, the override resolved for THIS scenario's
 * {@link ReadinessBudgetReceiver#readinessCheckpoint()}.
 *
 * <p>The override is read from the OSGi registry, exactly the way the ambient {@link
 * io.nxmatic.rke2lab.seed.broker.port.RunGate} is read: the host publishes ONE {@link
 * ReadinessOverrides} at boot (a single system-exported seam copy), every readiness scion resolves
 * it from its own bundle registry. No override published (or a host-flat play not bundle-loaded) ⇒
 * {@link ReadinessOverrides#NONE}, all annotation.
 *
 * <p>Opt-in by implementing {@link ReadinessBudgetReceiver}; a scenario that does not is left
 * untouched. A receiver that omits {@link ReadinessDeadlines} on its {@code @Test} is a wiring bug
 * — the point of the annotation is the visible default — so this fails loud rather than inventing
 * one.
 */
public final class ReadinessBudgetExtension implements BeforeTestExecutionCallback {

  @Override
  public void beforeTestExecution(ExtensionContext context) {
    if (!(context.getRequiredTestInstance() instanceof ReadinessBudgetReceiver receiver)) {
      return;
    }
    final ReadinessDeadlines deadlines =
        context.getRequiredTestMethod().getAnnotation(ReadinessDeadlines.class);
    if (deadlines == null) {
      throw new IllegalStateException(
          context.getRequiredTestClass().getSimpleName()
              + " is a ReadinessBudgetReceiver but its @Test carries no @ReadinessDeadlines —"
              + " declare the connect/ready defaults where they read");
    }
    final ReadinessDeadlineOverride override =
        resolveOverrides(receiver).forCheckpoint(receiver.readinessCheckpoint());
    receiver.receiveBudget(fold(deadlines, override));
  }

  /**
   * The ambient {@link ReadinessOverrides} the host published — resolved from the scion's OWN
   * bundle registry (the {@link io.nxmatic.rke2lab.seed.broker.port.RunGate} route). A host-flat
   * instance (not bundle-loaded) has no bundle registry and receives no override — {@link
   * ReadinessOverrides#NONE}. The registry is released at once (a one-shot lookup of a
   * host-registered service, not a delayed SCR component), the {@code ScenarioCellarExtension}
   * durable-lookup shape.
   */
  private static ReadinessOverrides resolveOverrides(ReadinessBudgetReceiver receiver) {
    if (FrameworkUtil.getBundle(receiver.getClass()) == null) {
      return ReadinessOverrides.NONE;
    }
    try (ScenarioRegistry registry = ScenarioRegistry.of(receiver)) {
      return registry.optional(ReadinessOverrides.class).orElse(ReadinessOverrides.NONE);
    }
  }

  private static ReadinessBudget fold(
      ReadinessDeadlines deadlines, ReadinessDeadlineOverride override) {
    final Duration connect =
        override.connect().orElseGet(() -> Duration.parse(deadlines.connect()));
    final Duration ready = override.ready().orElseGet(() -> Duration.parse(deadlines.ready()));
    return ReadinessBudget.of(connect, ready);
  }
}
