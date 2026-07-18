package io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container;

import io.nxmatic.rke2lab.osgi.boot.discovery.ClassRealm;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.support.store.Namespace;
import org.junit.platform.engine.support.store.NamespacedHierarchicalStore;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
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
    return run(hostLoader, engineClass, discovery, harvest, store -> {});
  }

  /**
   * As {@link #run(ClassLoader, Class, DiscoveryStrategy, HarvestStrategy)}, but the run happens
   * within an open {@link LauncherSession}: {@code seedSessionStore} is handed the session-level
   * store before discovery/execution, so the caller can seed values (e.g. host-facts) that a
   * harvest — or an extension ordered before the run — reads back. This is the inbound channel's
   * foundation: no ThreadLocal, the store crosses the launcher membrane.
   */
  public R run(
      ClassLoader hostLoader,
      Class<? extends TestEngine> engineClass,
      DiscoveryStrategy discovery,
      HarvestStrategy<R> harvest,
      Consumer<NamespacedHierarchicalStore<Namespace>> seedSessionStore)
      throws InterruptedException {
    final AtomicReference<R> out = new AtomicReference<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();

    final Thread worker =
        new Thread(
            () -> {
              try {
                out.set(execute(hostLoader, engineClass, discovery, harvest, seedSessionStore));
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
      HarvestStrategy<R> harvest,
      Consumer<NamespacedHierarchicalStore<Namespace>> seedSessionStore) {
    final LauncherConfig config =
        LauncherConfig.builder()
            .enableTestEngineAutoRegistration(false)
            .addTestEngines(instantiateEngine(engineClass))
            .build();

    try (LauncherSession session = LauncherFactory.openSession(config)) {
      seedSessionStore.accept(session.getStore());

      final LauncherDiscoveryRequest request =
          LauncherDiscoveryRequestBuilder.request()
              .selectors(discovery.selectors(wiringOf(hostLoader)))
              .build();

      return harvest.harvest(session.getLauncher(), request);
    }
  }

  /** The host bundle's wiring when {@code loader} is bundle-loaded, else empty (flat classpath). */
  private static Optional<BundleWiring> wiringOf(ClassLoader loader) {
    return ClassRealm.of(loader).adapt(BundleWiring.class);
  }

  private static TestEngine instantiateEngine(Class<? extends TestEngine> engineClass) {
    try {
      return engineClass.getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("cannot instantiate TestEngine " + engineClass.getName(), e);
    }
  }

  /**
   * Decorate a harvest so a scenario FAILURE surfaces as a thrown exception instead of a silent
   * pass. The JUnit Platform records test failures in listeners and returns from {@code
   * launcher.execute} normally — so a plain harvest that runs a scenario and returns a value
   * SWALLOWS the failure, and a driver reading a post-run holder (the runbook) then dies on a blind
   * NPE that masks the real cause. This attaches a {@link SummaryGeneratingListener}, delegates to
   * {@code inner} (which calls {@code execute}), logs the run tally, and — if any test failed —
   * rethrows the FIRST failure's own throwable (the scenario-step exception), so the driver sees
   * the true cause.
   *
   * <p>OPT-IN, never the default: the in-container front-doors ({@code *BddScenarios.run}) and
   * {@code InContainerJUnitRunner} deliberately HARVEST failure (a scion consults its doctor and
   * returns a FAILED envelope; the runner enumerates PASS/FAIL) — they must NOT rethrow. Only a
   * driver that plays a scenario expected to PASS (the host {@code Main}) wraps its harvest in
   * {@code failFast}.
   */
  public static <R> HarvestStrategy<R> failFast(Consumer<String> log, HarvestStrategy<R> inner) {
    return (launcher, request) -> {
      final SummaryGeneratingListener summary = new SummaryGeneratingListener();
      launcher.registerTestExecutionListeners(summary);
      final R result = inner.harvest(launcher, request);
      final TestExecutionSummary played = summary.getSummary();
      log.accept(
          "scenario run: "
              + played.getTestsSucceededCount()
              + " passed, "
              + played.getTestsFailedCount()
              + " failed, "
              + played.getTestsSkippedCount()
              + " skipped");
      if (played.getTotalFailureCount() > 0) {
        final TestExecutionSummary.Failure first = played.getFailures().get(0);
        throw new IllegalStateException(
            "the scenario failed at " + first.getTestIdentifier().getDisplayName(),
            first.getException());
      }
      return result;
    };
  }
}
