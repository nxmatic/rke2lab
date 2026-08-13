package io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container;

import com.tngtech.jgiven.impl.ScenarioExecutor;
import com.tngtech.jgiven.report.model.InvocationMode;
import com.tngtech.jgiven.report.model.NamedArgument;
import java.lang.reflect.Method;
import java.util.List;

/**
 * The RENDER lever for a SURVEY-INERT scenario (a pure probe under a surveying gate): every step
 * renders PENDING AND its body is SKIPPED — the probe never runs, so it contacts nothing. Unlike
 * the {@link PendingMarkingScenarioExecutor} (which lets bodies {@code proceed()} against a
 * surveying collaborator), a probe has no surveying collaborator to run against, so the body must
 * not run at all.
 *
 * <p>The mechanism, per scenario: after jGiven starts the scenario, this raises the step
 * interceptor's default invocation mode to {@code PENDING}. So {@code getInvocationMode} returns
 * {@code PENDING} for every step — the model records it PENDING (the listener is told that mode)
 * and the interceptor returns WITHOUT {@code proceed()} (its PENDING branch skips the body). No
 * listener wrap is needed: the interceptor reports PENDING directly, so the whole scenario reads
 * {@code SCENARIO_PENDING}. Installed by {@link SurveyRenderExtension} only for a {@link
 * SurveyInert} scenario under a surveying gate.
 */
public final class SurveyInertScenarioExecutor extends ScenarioExecutor {

  @Override
  public void startScenario(Class<?> testClass, Method method, List<NamedArgument> arguments) {
    super.startScenario(testClass, method, arguments);
    // PENDING (not disableMethodExecution): getInvocationMode returns PENDING → the step is
    // recorded
    // PENDING and its body is skipped (the interceptor's PENDING branch). disableMethodExecution
    // would instead force SKIPPED, which reads as a plain green — not the pending plan we want.
    methodInterceptor.setDefaultInvocationMode(InvocationMode.PENDING);
  }
}
