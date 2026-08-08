package io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The two readiness deadlines a scenario's {@code @Test} carries, DECLARED where they read — beside
 * the readiness checkpoint they bound, so the patience is visible in the code and not buried in a
 * config file. The stack config may still override either (via {@code
 * io.nxmatic.rke2lab.seed.broker.port.ReadinessOverrides}, keyed per checkpoint); the {@link
 * ReadinessBudgetExtension} resolves {@code effective = override.orElse(this-default)} and hands
 * the scenario a {@link io.nxmatic.rke2lab.osgi.runtime.readiness.ReadinessBudget}.
 *
 * <p>ISO-8601 strings because an annotation cannot hold a {@code Duration}; parsed with {@code
 * Duration.parse}. A {@code ReadinessBudgetReceiver} scenario MUST carry this on its {@code @Test}
 * — the extension fails loud otherwise, since the whole point is a visible default.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ReadinessDeadlines {

  /**
   * How long to keep retrying the connect — the reach phase that absorbs a cold boot / image
   * re-seed. Generous by default: the endpoint may not exist yet.
   */
  String connect() default "PT2M";

  /**
   * How long to await convergence once the endpoint answers — the native event channel's budget.
   */
  String ready() default "PT1M";
}
