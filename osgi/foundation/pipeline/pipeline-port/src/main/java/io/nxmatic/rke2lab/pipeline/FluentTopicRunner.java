package io.nxmatic.rke2lab.pipeline;

import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared executor for fluent-pipeline topics. Logs entry/exit boundaries with elapsed time and
 * wraps any throw inside the body as {@link TopicFailure} carrying the topic label; an optional
 * {@link OnFailure} is notified before the wrap.
 *
 * <p>An instance binds a {@code logScope} (the pipeline's name — "boot", "synthesis",
 * "manifest-synthesis") so a pipeline creates one runner and runs every topic through it, the scope
 * named once where it belongs instead of repeated at each call. The scope prefixes the boundary
 * logs, so interleaved pipelines stay legible.
 *
 * <p>See docs/architecture/patterns/fluent-pipeline-grammar.adoc for the grammar this implements.
 * PASSIVE (pure logic, slf4j-only) so it is consumed identically inside an OSGi bundle and flat on
 * the host classloader — the single home for what used to be the byte-identical {@code TopicRunner}
 * (host) and {@code SynthesisTopicRunner} (bundle).
 */
public final class FluentTopicRunner {

  private static final Logger LOG = LoggerFactory.getLogger(FluentTopicRunner.class);

  private final String logScope;

  public FluentTopicRunner(String logScope) {
    this.logScope = logScope;
  }

  public <S> S runDuring(String topic, S topicBuilder, Function<S, S> body, OnFailure onFailure) {
    final long startedAt = System.nanoTime();
    LOG.info("[{}] → entering {}", logScope, topic);
    try {
      body.apply(topicBuilder);
    } catch (Throwable cause) {
      onFailure.handle(topic, cause);
      throw new TopicFailure(topic, cause);
    }
    final long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
    LOG.info("[{}] ← leaving {} ({}ms)", logScope, topic, elapsedMs);
    return topicBuilder;
  }
}
