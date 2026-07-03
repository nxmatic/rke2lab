package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator.ReconciliationResult;
import io.nxmatic.rke2lab.pipeline.Topic;
import java.nio.file.Path;

/**
 * Bbox-reservation reconciliation topic. Pushes its reconciliation result through its {@link Sink}.
 */
public final class BboxTopic implements Topic.Execution {

  private final BboxReconciliationOrchestrator orchestrator;
  private final Path localWorktreePath;
  private final boolean failOnError;
  private final Sink sink;

  public BboxTopic(
      BboxReconciliationOrchestrator orchestrator,
      Path localWorktreePath,
      boolean failOnError,
      Sink sink) {
    this.orchestrator = orchestrator;
    this.localWorktreePath = localWorktreePath;
    this.failOnError = failOnError;
    this.sink = sink;
  }

  /** The write-face of the bbox topic. */
  public interface Sink extends Topic.Sink {
    void reconciliation(ReconciliationResult result);
  }

  @Override
  public String role() {
    return "bbox reconciliation";
  }

  public BboxTopic reconcileReservations() {
    sink.reconciliation(orchestrator.reconcile(localWorktreePath, failOnError));
    return this;
  }
}
