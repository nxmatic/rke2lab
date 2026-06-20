package io.nxmatic.rke2lab.pipeline;

import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared executor for fluent-pipeline topics. Logs entry/exit boundaries with elapsed time and
 * wraps any throw inside the body as {@link TopicFailure} carrying the topic label; an optional
 * {@link OnFailure} is notified before the wrap.
 *
 * <p>See docs/architecture/patterns/fluent-pipeline-grammar.adoc for the grammar this implements.
 * PASSIVE (pure logic, slf4j-only) so it is consumed identically inside an OSGi bundle and flat on
 * the host classloader — the single home for what used to be the byte-identical {@code TopicRunner}
 * (host) and {@code SynthesisTopicRunner} (bundle).
 */
public final class FluentTopicRunner {

  private static final Logger LOG = LoggerFactory.getLogger(FluentTopicRunner.class);

  private FluentTopicRunner() {}

  public static <S> S runDuring(
      String logScope, String topic, S stage, Function<S, S> body, OnFailure onFailure) {
    final long startedAt = System.nanoTime();
    LOG.info("→ entering {}", topic);
    try {
      body.apply(stage);
    } catch (Throwable cause) {
      if (onFailure != null) {
        onFailure.handle(topic, cause);
      }
      throw new TopicFailure(topic, cause);
    }
    final long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
    LOG.info("← leaving {} ({}ms)", topic, elapsedMs);
    return stage;
  }
}
