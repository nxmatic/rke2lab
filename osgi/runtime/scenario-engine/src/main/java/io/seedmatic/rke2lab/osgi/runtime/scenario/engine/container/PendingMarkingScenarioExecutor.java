package io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container;

import com.tngtech.jgiven.attachment.Attachment;
import com.tngtech.jgiven.impl.ScenarioExecutor;
import com.tngtech.jgiven.impl.intercept.ScenarioListener;
import com.tngtech.jgiven.report.model.InvocationMode;
import com.tngtech.jgiven.report.model.NamedArgument;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;

/**
 * The RENDER lever (E9): a jGiven {@link ScenarioExecutor} that narrates every step {@code PENDING}
 * while STILL running its body — how a surveyed scenario reads in the runbook (a plan, not a
 * result), decoupled from whether it touched anything (the TOUCH axis is the frontier's job).
 *
 * <p>The trick without reflection: jGiven's {@code StepInterceptorImpl} decides skip-vs-proceed
 * from its OWN {@code getInvocationMode} (which stays {@code NORMAL}), and SEPARATELY tells the
 * listener that mode so the model records it. We override {@link #setListener} — the one public
 * seam jGiven calls in {@code performInitialization} — to wrap the model-building listener in one
 * that rewrites {@code NORMAL → PENDING} on the way to the model. So the interceptor still {@code
 * proceed()}s (the step tree builds in full — no emptied sub-tree, the E9 pitfall), but every step
 * is REPORTED pending. {@code setListener} propagates the wrapped listener into the
 * (protected-final) interceptor itself, so we never touch that field.
 *
 * <p>Installed by {@link SurveyRenderExtension} only when the ambient run is surveying; a
 * cultivating run keeps jGiven's stock executor and renders results normally.
 */
public final class PendingMarkingScenarioExecutor extends ScenarioExecutor {

  @Override
  public void setListener(ScenarioListener listener) {
    super.setListener(new PendingMarking(listener));
  }

  /**
   * A forwarding {@link ScenarioListener} that rewrites a step's {@link InvocationMode#NORMAL} to
   * {@link InvocationMode#PENDING} as it reaches the model, and delegates everything else verbatim.
   * An already-{@code SKIPPED}/{@code PENDING} step (a {@code @Pending} method, a survey-inert
   * probe) passes through unchanged — the rewrite only reclassifies steps that WOULD have run
   * normally.
   */
  private record PendingMarking(ScenarioListener delegate) implements ScenarioListener {

    @Override
    public void stepMethodInvoked(
        Method method, List<NamedArgument> arguments, InvocationMode mode, boolean hasNestedSteps) {
      final InvocationMode rendered = mode == InvocationMode.NORMAL ? InvocationMode.PENDING : mode;
      delegate.stepMethodInvoked(method, arguments, rendered, hasNestedSteps);
    }

    @Override
    public void scenarioFailed(Throwable e) {
      delegate.scenarioFailed(e);
    }

    @Override
    public void scenarioAborted(Throwable e) {
      delegate.scenarioAborted(e);
    }

    @Override
    public void scenarioStarted(String description) {
      delegate.scenarioStarted(description);
    }

    @Override
    public void scenarioStarted(Class<?> testClass, Method method, List<NamedArgument> arguments) {
      delegate.scenarioStarted(testClass, method, arguments);
    }

    @Override
    public void introWordAdded(String introWord) {
      delegate.introWordAdded(introWord);
    }

    @Override
    public void stepCommentUpdated(String comment) {
      delegate.stepCommentUpdated(comment);
    }

    @Override
    public void stepMethodFailed(Throwable t) {
      delegate.stepMethodFailed(t);
    }

    @Override
    public void stepMethodAborted(Throwable t) {
      delegate.stepMethodAborted(t);
    }

    @Override
    public void stepMethodFinished(long durationInNanos, boolean hasNestedSteps) {
      delegate.stepMethodFinished(durationInNanos, hasNestedSteps);
    }

    @Override
    public void scenarioFinished() {
      delegate.scenarioFinished();
    }

    @Override
    public void attachmentAdded(Attachment attachment) {
      delegate.attachmentAdded(attachment);
    }

    @Override
    public void extendedDescriptionUpdated(String extendedDescription) {
      delegate.extendedDescriptionUpdated(extendedDescription);
    }

    @Override
    public void stepNameUpdated(String newStepName) {
      delegate.stepNameUpdated(newStepName);
    }

    @Override
    public void sectionAdded(String sectionTitle) {
      delegate.sectionAdded(sectionTitle);
    }

    @Override
    public void tagAdded(Class<? extends Annotation> annotationClass, String... values) {
      delegate.tagAdded(annotationClass, values);
    }
  }
}
