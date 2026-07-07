package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;
import java.util.Map;
import org.osgi.framework.BundleContext;

/**
 * A connection that serves a fixed set of services by type — no framework, no registry. It answers
 * {@link #awaitService(Class, long)} from an in-memory {@code type → instance} map (returning null
 * for an absent type, exactly as a real registry miss would), and never touches {@link #context()}.
 * The unit-test seam for a stage's {@code awaitService} calls when the concern under test is the
 * stage's LOGIC (a verdict decision), not the OSGi wiring — the wiring itself is proven separately
 * in a real Felix ({@code SystemdAdapterStageTest}).
 */
final class StubConnection implements OsgiConnection {

  private final Map<Class<?>, Object> services;

  private StubConnection(Map<Class<?>, Object> services) {
    this.services = services;
  }

  /** A connection serving each {@code type → instance} entry, and nothing else. */
  static StubConnection serving(Map<Class<?>, Object> services) {
    return new StubConnection(Map.copyOf(services));
  }

  @Override
  public <T> T awaitService(Class<T> type, long timeoutMillis) {
    return type.cast(services.get(type));
  }

  @Override
  public BundleContext context() {
    throw new UnsupportedOperationException("stub connection: the framework is never reached");
  }

  @Override
  public boolean ownsLifecycle() {
    return false;
  }

  @Override
  public void close() {
    // nothing to release: no framework was booted.
  }
}
