package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.attachment.Attachment;
import com.tngtech.jgiven.impl.ScenarioExecutor;
import com.tngtech.jgiven.impl.intercept.ScenarioListener;
import com.tngtech.jgiven.report.model.InvocationMode;
import com.tngtech.jgiven.report.model.NamedArgument;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;

/**
 * The E9 preview executor: renders a run as a rich PENDING <em>plan</em> without deferring the tree
 * construction. This is the decisive discovery of the BDD-pipeline POC (learning E9): any mechanism
 * that SKIPS the step bodies — jGiven's {@code dry-run} property, {@code disableMethodExecution()},
 * {@code setDefaultInvocationMode(PENDING)} — also skips the building of the nested sub-trees, so a
 * preview would render empty phases. The fix decouples RENDERING from EXECUTION: bodies still
 * {@code proceed()} (the whole tree builds against inert collaborators), but the {@link
 * InvocationMode} that REACHES the model is rewritten {@code NORMAL → PENDING}. The runbook then
 * reads as the complete plan — every phase and every nested sub-tree — marked pending, never
 * traversed-and-empty.
 *
 * <p>This is a pure rendering concern, orthogonal to infra safety: whether a live crossing actually
 * touches the system is decided by the collaborators (the {@link
 * io.nxmatic.rke2lab.pulumi.edge.LiveGate} they consult, Pulumi's own {@code isDryRun()} in {@code
 * IncusResourceBootstrap}). The executor only governs how the played run is NARRATED.
 *
 * <p>Installed conditionally by {@link HostSeeder} when the run is a preview; in a live run
 * jGiven's default executor stays, so this decorate is inert there. It reaches jGiven's listener
 * seam through the public {@link #setListener} override — no reflection, no fork.
 */
public final class PendingMarkingScenarioExecutor extends ScenarioExecutor {

  private final boolean preview;

  public PendingMarkingScenarioExecutor(boolean preview) {
    this.preview = preview;
  }

  @Override
  public void setListener(ScenarioListener listener) {
    super.setListener(preview ? new PendingMarkingListener(listener) : listener);
  }

  /**
   * Rewrites the invocation mode reaching the model {@code NORMAL → PENDING}; forwards all else.
   */
  private static final class PendingMarkingListener implements ScenarioListener {

    private final ScenarioListener delegate;

    PendingMarkingListener(ScenarioListener delegate) {
      this.delegate = delegate;
    }

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
    public void stepNameUpdated(String newName) {
      delegate.stepNameUpdated(newName);
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
