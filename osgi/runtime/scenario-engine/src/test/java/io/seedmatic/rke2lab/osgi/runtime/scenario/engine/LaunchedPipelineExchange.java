package io.seedmatic.rke2lab.osgi.runtime.scenario.engine;

import com.tngtech.jgiven.report.model.ReportModel;
import io.seedmatic.rke2lab.osgi.runtime.junit.launcher.JUnitLauncherCore;
import java.util.Objects;

/**
 * What a driver hands a launcher-played pipeline across the launcher membrane: the live world to
 * connect to, and the runbook to play into. An immutable record — both values are known when the
 * driver opens it, so nothing is captured back afterwards. The driver creates an empty {@link
 * ReportModel}, injects it here, and the played scenario {@code setModel}s it so jGiven fills THAT
 * instance in place; the driver reads its own reference once the run finishes (the same
 * inject-the-model idiom the prod checkpoints use with {@code Scenario.create + setModel}). So the
 * runbook needs no return channel — only this one-way hand-in.
 *
 * <p>{@link JUnitLauncherCore} instantiates the pipeline itself, so the sole way it can reach this
 * record is thread-confined state on the single worker thread the whole play runs on (jGiven's own
 * {@code ScenarioHolder} is the same shape). {@link #bind} binds one exchange to that thread and
 * returns it {@code AutoCloseable}; the pipeline reads it via {@link #current()} — the one
 * irreducible membrane crossing. From the driver's side there is no static: it holds the record it
 * bound and the {@code ReportModel} it created.
 *
 * <p>Test-scope: the exchange exists only so the socle's own acceptance test can drive the launcher
 * over a testkit-booted world (the engine test module stages no bundles for {@code
 * OsgiConnection.embedded()}). Increment 2's real seed runs inside an exec that stages bundles, so
 * its discipline self-boots via {@code embedded()} and needs no exchange.
 */
record LaunchedPipelineExchange(OsgiConnection connection, ReportModel runbook)
    implements AutoCloseable {

  // The one irreducible static: the pipeline is constructed by the launcher, so the only channel
  // from the driver to it is thread-confined state, bound for the duration of one run.
  private static final ThreadLocal<LaunchedPipelineExchange> BOUND = new ThreadLocal<>();

  LaunchedPipelineExchange {
    Objects.requireNonNull(connection, "connection");
    Objects.requireNonNull(runbook, "runbook");
  }

  /**
   * Bind an exchange over {@code connection} + {@code runbook} to the calling (worker) thread and
   * return it. Called inside the harvest, which runs on {@code JUnitLauncherCore}'s worker thread —
   * the same thread the pipeline plays on, so {@link #current()} resolves there.
   */
  static LaunchedPipelineExchange bind(OsgiConnection connection, ReportModel runbook) {
    final LaunchedPipelineExchange exchange = new LaunchedPipelineExchange(connection, runbook);
    BOUND.set(exchange);
    return exchange;
  }

  /** The exchange bound to this thread — the pipeline's single reach back to the driver. */
  static LaunchedPipelineExchange current() {
    return Objects.requireNonNull(BOUND.get(), "no pipeline exchange bound to this thread");
  }

  /** Unbind from the thread once the run is harvested. */
  @Override
  public void close() {
    BOUND.remove();
  }
}
