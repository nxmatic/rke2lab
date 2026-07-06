package io.nxmatic.rke2lab.osgi.runtime.scenario.engine;

import io.nxmatic.rke2lab.osgi.runtime.framework.BootedFramework;
import io.nxmatic.rke2lab.osgi.runtime.framework.FrameworkLaunchPipeline;
import org.osgi.framework.BundleContext;
import org.osgi.framework.launch.Framework;

/**
 * A connection to the OSGi world — the first step of a seed scenario ("the OSGi world is
 * connected") and the socle's lifecycle handle (spec Figure 3). It is a CONTRACT over a live {@link
 * BundleContext}, NOT a boot mechanism: it exposes only what the socle consumes — {@link
 * #context()} to reach the world (the {@link StartLevelLever} adapts {@code getBundle(0)} to {@code
 * FrameworkStartLevel}), {@link #ownsLifecycle()} to know whether teardown should stop the world,
 * and {@link #close()} to release it. Asking for a capability (a context + a close), not a producer
 * identity, is what lets one contract serve every boot: embedded, attached, and — later — remote.
 *
 * <p>Two faces (Figure 3):
 *
 * <ul>
 *   <li>{@link #embedded()} — REALISED: bootstraps Felix in-process (the prod boot from the staged
 *       bundles a deployed exec-jar carries), owns the lifecycle, and its {@code close()} stops the
 *       world. This is the seed's first step in increment 2.
 *   <li>{@link #remote(String)} — NAMED, not built: would attach to a running world and own
 *       nothing, so its {@code close()} would merely detach. Throws until a real client-framework
 *       case arrives (the spec's YAGNI). The fine contract is exactly what makes it cheap to add:
 *       it will return a connection with {@code ownsLifecycle() == false} and a detach action.
 * </ul>
 *
 * <p>{@link #over(BundleContext, boolean, Runnable)} wraps an already-booted world under the same
 * contract — how a test opens a connection over a Felix booted by the testkit, and how {@code
 * embedded()} itself is expressed over the booted framework.
 */
public interface OsgiConnection extends AutoCloseable {

  /**
   * The live world's bundle context — the one capability the socle reaches the framework through.
   */
  BundleContext context();

  /**
   * Whether {@link #close()} must stop the world (embedded) or merely detach from it (attached).
   */
  boolean ownsLifecycle();

  /** Release the connection: stop the world if owned, else detach — never throws checked. */
  @Override
  void close();

  /**
   * The {@link Framework} this connection is attached to — the system bundle IS the Framework
   * ({@code getBundle(0)}). A cast, not an {@code adapt}: the system bundle's runtime identity, not
   * a capability face. For phases that need the {@code BootedFramework}-shaped service lookup
   * ({@link BootedFramework#attached}) without owning the boot.
   */
  default Framework framework() {
    return (Framework) context().getBundle(0);
  }

  /**
   * Bootstrap Felix in-process and own its lifecycle — the prod boot (spec Figure 3, REALISED).
   * Boots from the bundles staged under {@code META-INF/bundles/} that a deployed exec-jar carries
   * (via {@link FrameworkLaunchPipeline#embedded()}), so it runs in the seed's own artifact, not in
   * a bare library module. {@code close()} stops the world.
   */
  static OsgiConnection embedded() {
    final BootedFramework booted = FrameworkLaunchPipeline.embedded().launch();
    return over(booted.context(), true, booted::close);
  }

  /**
   * Wrap an ALREADY-booted world under the connection contract: {@code context} is the live world,
   * {@code ownsLifecycle} says whether {@code close()} stops it, {@code onClose} is the teardown to
   * run on {@link #close()} (stop the world when owned, detach when attached).
   */
  static OsgiConnection over(BundleContext context, boolean ownsLifecycle, Runnable onClose) {
    return new OsgiConnection() {
      @Override
      public BundleContext context() {
        return context;
      }

      @Override
      public boolean ownsLifecycle() {
        return ownsLifecycle;
      }

      @Override
      public void close() {
        onClose.run();
      }
    };
  }

  /**
   * Attach to a running world reachable at {@code endpoint} — NAMED, not built (spec Figure 3).
   * Would own nothing; its {@code close()} would detach, leaving the world alive. Throws until a
   * real client-framework case arrives (§yagni).
   */
  static OsgiConnection remote(String endpoint) {
    throw new UnsupportedOperationException("remote OSGi connection not yet realised: " + endpoint);
  }
}
