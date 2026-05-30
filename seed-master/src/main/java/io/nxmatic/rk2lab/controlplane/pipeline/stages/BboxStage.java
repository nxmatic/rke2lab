package io.nxmatic.rk2lab.controlplane.pipeline.stages;

import io.nxmatic.rk2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rk2lab.controlplane.bbox.BboxReconciliationOrchestrator.ReconciliationResult;
import java.nio.file.Path;
import java.util.function.Consumer;

public final class BboxStage {

  private final BboxReconciliationOrchestrator orchestrator;
  private final Path localWorktreePath;
  private final boolean failOnError;
  private final Consumer<ReconciliationResult> sink;

  public BboxStage(
      BboxReconciliationOrchestrator orchestrator,
      Path localWorktreePath,
      boolean failOnError,
      Consumer<ReconciliationResult> sink) {
    this.orchestrator = orchestrator;
    this.localWorktreePath = localWorktreePath;
    this.failOnError = failOnError;
    this.sink = sink;
  }

  public BboxStage reconcileReservations() {
    sink.accept(orchestrator.reconcile(localWorktreePath, failOnError));
    return this;
  }
}
