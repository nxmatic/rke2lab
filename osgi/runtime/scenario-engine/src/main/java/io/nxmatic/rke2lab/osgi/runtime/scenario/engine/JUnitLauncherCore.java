package io.nxmatic.rke2lab.osgi.runtime.scenario.engine;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleReference;
import org.osgi.framework.wiring.BundleWiring;

/**
 * Runs a JUnit Platform {@link Launcher} inside Felix on a dedicated host-bound thread, the engine
 * registered explicitly, the three OSGi crossings handled — with discovery and harvest injected as
 * strategies. Extracted from {@code InContainerJUnitRunner} so the same core serves BOTH the
 * in-container test envelope ({@code *Test} enumeration + PASS/FAIL lines) AND a runtime pipeline
 * (a named scenario + a jGiven {@code ReportModel}); the runner is now a thin consumer of it.
 *
 * <p>Direction: the runtime OWNS this, the test CONSUMES it — JUnit is runtime-scope machinery now
 * (the dogfooding promotion), so its neutral core lives in the engine module, not the testkit.
 *
 * <p><b>The three OSGi crossings, each handled here:</b>
 *
 * <ol>
 *   <li><b>Thread context classloader.</b> The launcher's {@code ServiceLoader} and Jupiter's
 *       discovery read the thread context classloader; surefire leaves it on the flat app
 *       classpath. So the whole launch runs on a DEDICATED thread whose context classloader is the
 *       host's — the membrane between the OSGi world and the bare-JVM world.
 *   <li><b>Engine registration.</b> The engine is registered EXPLICITLY ({@link LauncherConfig},
 *       auto-registration off): {@code ServiceLoader} cannot cross the OSGi boundary. The engine
 *       {@link Class} is passed in — so the caller's bytecode references it and bnd wires the
 *       engine bundle in; host-loaded, its {@code TestEngine} supertype matches the launcher's.
 *   <li><b>Class discovery.</b> The core hands the {@link DiscoveryStrategy} the host bundle's
 *       {@link BundleWiring} when running in-container ({@link Optional#empty()} on the flat
 *       classpath), so an in-container strategy can enumerate via {@link
 *       BundleWiring#listResources} (Felix {@code bundle://} URLs are invisible to Jupiter's
 *       file-directory scanner).
 * </ol>
 *
 * @param <R> the harvested result type (see {@link HarvestStrategy})
 */
public final class JUnitLauncherCore<R> {

  /**
   * Run {@code engineClass} against {@code discovery}'s selectors on a dedicated thread bound to
   * {@code hostLoader}, returning {@code harvest}'s value. When {@code hostLoader} is a {@link
   * BundleReference} (running in-container) the host bundle's {@link BundleWiring} is passed to the
   * discovery; on the flat host classpath it receives {@link Optional#empty()} and discovers by
   * named class.
   */
  public R run(
      ClassLoader hostLoader,
      Class<? extends TestEngine> engineClass,
      DiscoveryStrategy discovery,
      HarvestStrategy<R> harvest)
      throws InterruptedException {
    final AtomicReference<R> out = new AtomicReference<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();

    final Thread worker =
        new Thread(
            () -> {
              try {
                out.set(execute(hostLoader, engineClass, discovery, harvest));
              } catch (Throwable t) {
                failure.set(t);
              }
            },
            "junit-launcher-core");
    worker.setContextClassLoader(hostLoader);
    worker.start();
    worker.join();

    if (failure.get() != null) {
      throw new IllegalStateException("in-container JUnit run failed", failure.get());
    }
    return out.get();
  }

  private R execute(
      ClassLoader hostLoader,
      Class<? extends TestEngine> engineClass,
      DiscoveryStrategy discovery,
      HarvestStrategy<R> harvest) {
    final Launcher launcher =
        LauncherFactory.create(
            LauncherConfig.builder()
                .enableTestEngineAutoRegistration(false)
                .addTestEngines(instantiateEngine(engineClass))
                .build());

    final LauncherDiscoveryRequest request =
        LauncherDiscoveryRequestBuilder.request()
            .selectors(discovery.selectors(wiringOf(hostLoader)))
            .build();

    return harvest.harvest(launcher, request);
  }

  /** The host bundle's wiring when {@code loader} is bundle-loaded, else empty (flat classpath). */
  private static Optional<BundleWiring> wiringOf(ClassLoader loader) {
    if (loader instanceof BundleReference bundleReference) {
      final Bundle host = bundleReference.getBundle();
      return Optional.of(host.adapt(BundleWiring.class));
    }
    return Optional.empty();
  }

  private static TestEngine instantiateEngine(Class<? extends TestEngine> engineClass) {
    try {
      return engineClass.getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("cannot instantiate TestEngine " + engineClass.getName(), e);
    }
  }
}
