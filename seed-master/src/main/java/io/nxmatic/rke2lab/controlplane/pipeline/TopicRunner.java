package io.nxmatic.rke2lab.controlplane.pipeline;

import io.nxmatic.rke2lab.controlplane.SeedLog;
import java.util.function.Function;

/**
 * Shared executor for fluent-pipeline topics. Logs entry/exit boundaries with elapsed time and
 * wraps any throw inside the body as {@link PipelineStageFailure} carrying the topic label. Each
 * pipeline picks its own log scope so reactor output stays attributable.
 *
 * <p>See docs/fluent-pipeline-grammar.adoc for the grammar this implements.
 */
public final class TopicRunner {

  private TopicRunner() {}

  public static <S> S runDuring(
      String logScope, String topic, S stage, Function<S, S> body, OnFailure onFailure) {
    final long startedAt = System.nanoTime();
    SeedLog.info(logScope, "→ entering " + topic);
    try {
      body.apply(stage);
    } catch (Throwable cause) {
      if (onFailure != null) {
        onFailure.handle(topic, cause);
      }
      throw new PipelineStageFailure(topic, cause);
    }
    final long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
    SeedLog.info(logScope, "← leaving " + topic + " (" + elapsedMs + "ms)");
    return stage;
  }
}
