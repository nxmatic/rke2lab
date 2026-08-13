package io.seedmatic.rke2lab.manifests.internal.synthesis;

import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared executor for synthesis phases. Logs entry/exit boundaries with elapsed time and wraps any
 * throw inside the body as {@link PhaseFailure} carrying the phase label; an optional {@link
 * OnFailure} is notified before the wrap.
 *
 * <p>An instance binds a {@code logScope} (the pipeline's name — "manifest-synthesis", "synthesis")
 * so a pipeline creates one runner and runs every phase through it, the scope named once where it
 * belongs instead of repeated at each call. The scope prefixes the boundary logs, so interleaved
 * pipelines stay legible.
 *
 * <p>PASSIVE (pure logic, slf4j-only) — the single home for what the two manifests synthesis
 * pipelines drive their phases through.
 */
public final class PhaseRunner {

  private static final Logger LOG = LoggerFactory.getLogger(PhaseRunner.class);

  private final String logScope;

  public PhaseRunner(String logScope) {
    this.logScope = logScope;
  }

  public <S extends Phase> S runDuring(
      String phase, S phaseBuilder, Function<S, S> body, OnFailure onFailure) {
    final long startedAt = System.nanoTime();
    LOG.info("[{}] → entering {}", logScope, phase);
    try {
      body.apply(phaseBuilder);
    } catch (Throwable cause) {
      onFailure.handle(phase, cause);
      throw new PhaseFailure(phase, cause);
    }
    final long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
    LOG.info("[{}] ← leaving {} ({}ms)", logScope, phase, elapsedMs);
    return phaseBuilder;
  }
}
