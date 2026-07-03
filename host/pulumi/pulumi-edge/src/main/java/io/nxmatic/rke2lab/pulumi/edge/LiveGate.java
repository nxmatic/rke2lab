package io.nxmatic.rke2lab.pulumi.edge;

import com.pulumi.deployment.Deployment;
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
   * The gate for a run, across all three launch modes: closed ONLY for a Pulumi {@code preview}
   * (dry-run under Pulumi management); open for a Pulumi {@code up} AND for a standalone run
   * ({@code pulumiMode == false}) — both touch the live system. The {@code &&} short-circuit means
   * {@code Deployment.getInstance()} is read only when actually under Pulumi, never standalone
   * (where no deployment exists). This is the ONE place the dry-run flag is read.
   */
  public static LiveGate forRun(boolean pulumiMode) {
    return new LiveGate(!(pulumiMode && Deployment.getInstance().isDryRun()));
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
