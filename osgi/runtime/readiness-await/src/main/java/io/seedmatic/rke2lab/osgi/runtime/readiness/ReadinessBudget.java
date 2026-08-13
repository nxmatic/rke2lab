package io.seedmatic.rke2lab.osgi.runtime.readiness;

import java.time.Duration;
import java.util.Objects;

/**
 * The two-tier deadline a readiness seam is handed — the VALUE the scenario resolves (from its
 * {@code @ReadinessDeadlines} defaults, overridden by the stack config) and passes to {@code
 * awaitReady}, and the edge unpacks to build a {@link ReadinessAwait}. It lives here, beside the
 * driver, so the seam type and the driver share ONE pure-JDK home the contract, the edge, and the
 * scenario engine all depend on without dragging a heavier module in.
 *
 * <ul>
 *   <li>{@code connect} bounds the reach phase — the connect-retry that absorbs a cold boot or a
 *       fresh image re-seed (a closed dbus socket, a down apiserver). This is where the old
 *       deadline-poll watcher's patience now lives.
 *   <li>{@code ready} bounds the convergence wait once the endpoint answers — the native event
 *       channel's budget.
 *   <li>{@code interval} is the connect-retry poll period — not a deadline the scenario headlines,
 *       so it is not an {@code @ReadinessDeadlines} attribute; it defaults to {@link
 *       #DEFAULT_INTERVAL} and the factory carries it.
 * </ul>
 */
public record ReadinessBudget(Duration connect, Duration ready, Duration interval) {

  /** The connect-retry poll period, when nothing overrides it — brisk enough to catch a boot. */
  public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(2);

  public ReadinessBudget {
    Objects.requireNonNull(connect, "connect");
    Objects.requireNonNull(ready, "ready");
    Objects.requireNonNull(interval, "interval");
  }

  /** A budget with the two headline deadlines and the default connect-retry interval. */
  public static ReadinessBudget of(Duration connect, Duration ready) {
    return new ReadinessBudget(connect, ready, DEFAULT_INTERVAL);
  }
}
