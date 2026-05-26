package io.nxmatic.rk2lab.controlplane.pipeline;

import io.nxmatic.rk2lab.controlplane.SeedLog;
import io.nxmatic.rk2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rk2lab.controlplane.incus.IncusResourceBootstrap;
import io.nxmatic.rk2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rk2lab.controlplane.resources.ResourceManager;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

final class PipelineState {

  final BootstrapConfig config;
  final ControlplanePolicy policy;
  BootstrapOptions options;
  Consumer<String> readinessLogger;
  boolean pulumiMode;
  BboxReconciliationOrchestrator bboxOrchestrator;
  ResourceManager resourceManager;
  OutputBuilder outputBuilder;

  BboxReconciliationOrchestrator.ReconciliationResult bboxResult;
  IncusResourceBootstrap.BootstrapResult bootstrapResult;
  Map<String, Object> systemdAdapterLaunchSummary;
  ResourceManager.ResourceCreationResult resourceResult;

  OnFailure onFailure;

  PipelineState(BootstrapConfig config, ControlplanePolicy policy) {
    this.config = config;
    this.policy = policy;
  }

  <S, R> R runDuring(String topic, S stage, Function<S, S> body) {
    final long startedAt = System.nanoTime();
    SeedLog.info("pipeline", "→ entering " + topic);
    try {
      body.apply(stage);
    } catch (Throwable cause) {
      final PipelineStageFailure failure = new PipelineStageFailure(topic, cause);
      if (onFailure != null) {
        onFailure.handle(topic, cause);
      }
      throw failure;
    }
    final long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
    SeedLog.info("pipeline", "← leaving " + topic + " (" + elapsedMs + "ms)");
    @SuppressWarnings("unchecked")
    final R cast = (R) this;
    return cast;
  }
}
