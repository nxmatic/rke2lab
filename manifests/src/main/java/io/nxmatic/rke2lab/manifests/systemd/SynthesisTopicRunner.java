package io.nxmatic.rke2lab.manifests.systemd;

import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared executor for synthesis pipeline topics. Logs entry/exit boundaries with elapsed time and
 * wraps any throw inside the body as {@link SynthesisStageFailure} carrying the topic label.
 *
 * <p>See docs/fluent-pipeline-grammar.adoc for the grammar this implements.
 */
final class SynthesisTopicRunner {

  private static final Logger LOG = LoggerFactory.getLogger(SynthesisTopicRunner.class);

  private SynthesisTopicRunner() {}

  static <S> S runDuring(
      String logScope, String topic, S stage, Function<S, S> body, SynthesisOnFailure onFailure) {
    final long startedAt = System.nanoTime();
    LOG.info("→ entering {}", topic);
    try {
      body.apply(stage);
    } catch (Throwable cause) {
      if (onFailure != null) {
        onFailure.handle(topic, cause);
      }
      throw new SynthesisStageFailure(topic, cause);
    }
    final long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
    LOG.info("← leaving {} ({}ms)", topic, elapsedMs);
    return stage;
  }
}
