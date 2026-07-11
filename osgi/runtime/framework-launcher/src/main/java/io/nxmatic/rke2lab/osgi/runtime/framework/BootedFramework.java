package io.nxmatic.rke2lab.osgi.runtime.framework;

import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.launch.Framework;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A live embedded Felix framework, booted by {@link FrameworkLauncher} — the host SEAM. The host
 * world (flat JCL) reads model services out of it by type ({@link #awaitService(Class, long)}) or
 * by name ({@link #awaitService(String, long)}), then {@link #close() closes} it. The boot ACT
 * produced this; the boot DECISION ({@code BootPlan}) is upstream and framework-free.
 *
 * <p>{@code AutoCloseable} so an entrypoint that owns the boot span ({@code
 * FrameworkLaunch.embedded()}) can try-with-resources it; the test executor hands it out and closes
 * it in its own lifecycle.
 */
public final class BootedFramework implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(BootedFramework.class);

  private final Framework framework;
  private final boolean owns;

  BootedFramework(Framework framework) {
    this(framework, true);
  }

  private BootedFramework(Framework framework, boolean owns) {
    this.framework = framework;
    this.owns = owns;
  }

  /**
   * A view that ATTACHES to an already-running framework (e.g. reached from an {@code
   * OsgiConnection}) without owning its lifecycle: {@link #close()} is a no-op, because the
   * attacher did not boot it and must not stop it. Parallel to {@code
   * OsgiConnection.over(ownsLifecycle = false)} — a phase that needs the service-lookup shape of a
   * {@code BootedFramework} but did not perform the boot.
   */
  public static BootedFramework attached(Framework framework) {
    return new BootedFramework(framework, false);
  }

  /**
   * The booted framework's bundle context, for the host seam to read services from the registry.
   */
  public BundleContext context() {
    return framework.getBundleContext();
  }

  /**
   * Resolve a single service of {@code type} from the registry, waiting up to {@code timeoutMillis}
   * for SCR to publish it (a component's service appears only after its mandatory references bind).
   */
  public <T> T awaitService(Class<T> type, long timeoutMillis) {
    final ServiceTracker<T, T> tracker = new ServiceTracker<>(context(), type, null);
    tracker.open();
    try {
      return tracker.waitForService(timeoutMillis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted awaiting service " + type.getName(), ex);
    } finally {
      tracker.close();
    }
  }

  /** By-name variant for a service the host cannot type (its package is bundle-internal). */
  public Object awaitService(String className, long timeoutMillis) {
    final ServiceTracker<Object, Object> tracker = new ServiceTracker<>(context(), className, null);
    tracker.open();
    try {
      return tracker.waitForService(timeoutMillis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted awaiting service " + className, ex);
    } finally {
      tracker.close();
    }
  }

  @Override
  public void close() {
    if (!owns) {
      return; // attached, not owned — do not stop a framework we did not boot
    }
    try {
      framework.stop();
      framework.waitForStop(5000);
    } catch (BundleException | InterruptedException ex) {
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      LOG.warn("OSGi runtime shutdown was not clean", ex);
    }
  }
}
