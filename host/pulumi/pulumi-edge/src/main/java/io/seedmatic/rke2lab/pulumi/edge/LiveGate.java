package io.seedmatic.rke2lab.pulumi.edge;

import java.util.function.Supplier;

/**
 * The living boundary: may an action touch the REAL system (probe a host, wait on a file, mutate
 * infrastructure)? Open on a real run ({@code pulumi up} or a standalone execution), closed during
 * {@code pulumi preview} (dry-run), where touching the live system would hang or lie.
 *
 * <p>This is the single source of truth for that state. It replaces the scattered inline reads of
 * {@code Deployment.getInstance().isDryRun()} — a domain no longer reaches for the global flag; it
 * is handed this gate and asks it. The pipeline holds the gate and decides each crossing through
 * {@link #through(Supplier, Supplier)}; the domain only supplies the two branches.
 *
 * <p>A mechanism, not an orchestrator: it answers {@link #isOpen()} and offers one combinator. The
 * pipeline is the sole orchestrator of when a crossing happens.
 */
public final class LiveGate {

  private final boolean open;

  private LiveGate(boolean open) {
    this.open = open;
  }

  /** A gate that always permits the live crossing (a real run). */
  public static LiveGate opened() {
    return new LiveGate(true);
  }

  /** A gate that always defers the live crossing (a preview). */
  public static LiveGate closed() {
    return new LiveGate(false);
  }

  /**
   * The gate projected from a {@link RunMode}: open when the mode {@link RunMode#playsLive() plays
   * live} (a Pulumi {@code up} OR a standalone run — both touch the live system), closed only for a
   * Pulumi {@code preview}. {@code LiveGate} is one of {@code RunMode}'s two projections; it names
   * no {@code com.pulumi} type, so the domain consumes it without seeing Pulumi.
   */
  public static LiveGate forRun(RunMode runMode) {
    return new LiveGate(runMode.playsLive());
  }

  /** Whether the live crossing is permitted (a real run) rather than deferred (a preview). */
  public boolean isOpen() {
    return open;
  }

  /**
   * Cross the boundary: run {@code live} when the gate is open, {@code deferred} when it is closed.
   * The domain supplies both branches; the gate chooses. Neither branch is evaluated until chosen.
   */
  public <T> T through(Supplier<T> live, Supplier<T> deferred) {
    return open ? live.get() : deferred.get();
  }
}
