package io.seedmatic.rke2lab.osgi.runtime.readiness;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The connect-retry + two-tier-deadline skeleton the readiness edges share. It owns the control
 * flow both readiness edges follow — reach the endpoint (retried until {@code connectTimeout}),
 * then while connected hand the remaining {@code readinessTimeout} to a domain-native convergence
 * wait — and nothing domain-specific.
 *
 * <p>The event channel is NOT part of the skeleton because it does not generalise: the systemd edge
 * wakes-and-rechecks on raw dbus signals ({@code JobRemoved} / {@code PropertiesChanged} on {@code
 * rke2lab.target}), while the cluster edge lets fabric8 {@code waitUntilCondition} encapsulate the
 * wait. So each edge injects its convergence wait as {@code awaitConvergence}; the skeleton only
 * gets it connected and bounds it.
 *
 * <p>Two INDEPENDENT budgets, because reaching an endpoint is not the same as it converging:
 *
 * <ul>
 *   <li>{@code connectTimeout} bounds the connect-retry — you cannot listen for an endpoint that
 *       does not exist yet (a closed dbus socket refuses, a down apiserver streams nothing), so the
 *       reach phase is an irreducible poll. A cold boot or a fresh image re-seed lives here.
 *   <li>{@code readinessTimeout} bounds the convergence wait once the endpoint answers — this is
 *       where the native event channel earns its keep (push, not sampling).
 * </ul>
 */
public final class ReadinessAwait {

  private final Duration connectInterval;
  private final Duration connectTimeout;
  private final Duration readinessTimeout;
  private final Consumer<String> progress;

  public ReadinessAwait(
      Duration connectInterval,
      Duration connectTimeout,
      Duration readinessTimeout,
      Consumer<String> progress) {
    this.connectInterval = Objects.requireNonNull(connectInterval, "connectInterval");
    this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
    this.readinessTimeout = Objects.requireNonNull(readinessTimeout, "readinessTimeout");
    this.progress = Objects.requireNonNull(progress, "progress");
  }

  /**
   * Reach the endpoint, then await convergence. Returns the domain snapshot produced by {@code
   * awaitConvergence} once connected — converged, or the last not-ready state at {@code
   * readinessTimeout}. If the endpoint stays unreachable through {@code connectTimeout} (the node
   * never booted far enough) this throws a {@link ReadinessAwaitException} CARRYING the last
   * connect failure as cause, so the caller sees WHY it was unreachable (a refused socket, a
   * missing transport) rather than a blind absence. The channel is opened by {@code connect} and
   * closed here (it is {@link AutoCloseable}), so the convergence wait always runs inside a live
   * connection and never leaks it.
   *
   * @param connect one reach attempt: an open channel, or empty (or a thrown runtime exception,
   *     remembered as the last "not up yet" cause) when the endpoint is not reachable yet
   * @param awaitConvergence given the open channel and the remaining convergence budget, block
   *     until the domain converges (or the budget elapses) and return its final snapshot
   */
  public <C extends AutoCloseable, S> S await(
      Supplier<Optional<C>> connect, BiFunction<C, Duration, S> awaitConvergence) {
    try (C channel = reach(connect)) {
      return awaitConvergence.apply(channel, readinessTimeout);
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception closeFailure) {
      throw new ReadinessAwaitException("readiness await failed once connected", closeFailure);
    }
  }

  private <C> C reach(Supplier<Optional<C>> connect) {
    final long deadline = System.nanoTime() + connectTimeout.toNanos();
    int attempts = 0;
    Throwable lastFailure = null;
    while (true) {
      attempts++;
      try {
        final Optional<C> channel = connect.get();
        if (channel.isPresent()) {
          progress.accept("endpoint reachable after " + attempts + " attempt(s)");
          return channel.orElseThrow();
        }
      } catch (RuntimeException notUpYet) {
        // Connection refused / endpoint not booted yet — a normal outcome during the boot window.
        // Remembered (not swallowed) so the give-up carries WHY it never came up.
        lastFailure = notUpYet;
      }
      if (System.nanoTime() >= deadline) {
        throw new ReadinessAwaitException(
            "endpoint unreachable through the connect timeout "
                + connectTimeout
                + " ("
                + attempts
                + " attempts) — the node never booted far enough",
            lastFailure);
      }
      sleep(connectInterval);
    }
  }

  private static void sleep(Duration interval) {
    try {
      Thread.sleep(Math.max(0L, interval.toMillis()));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new ReadinessAwaitException("interrupted while retrying the connect", interrupted);
    }
  }

  /**
   * The unchecked failure the skeleton raises for a broken connect-retry or a channel-close fault.
   */
  public static final class ReadinessAwaitException extends RuntimeException {
    ReadinessAwaitException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
