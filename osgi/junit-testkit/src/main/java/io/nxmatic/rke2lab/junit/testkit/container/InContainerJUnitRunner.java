package io.nxmatic.rke2lab.junit.testkit.container;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleReference;
import org.osgi.framework.wiring.BundleWiring;

/**
 * The in-framework half of the OSGi test-fragment model — the in-container twin of {@code
 * FelixFrameworkExtension} (its JVM-side counterpart). A host {@code -test} fragment, which shares
 * its host bundle's classloader, constructs one of these (reflectively, THROUGH that loader) and
 * calls {@link #run()} so a real JUnit Platform Launcher runs INSIDE the framework, discovering and
 * executing the fragment's {@code @Test} classes white-box (exactly as the host's own code sees
 * them). {@link #run()} returns plain {@link String}s so no JUnit type ever crosses into the
 * calling JVM world.
 *
 * <p>An instance, not a static helper: the host classloader, the engine class, and the package to
 * scan are the collaborators it runs against — passed in, held as fields, explicit in the call
 * graph.
 *
 * <p><b>Three OSGi crossings, each handled:</b>
 *
 * <ol>
 *   <li><b>Thread context classloader.</b> The launcher's {@code ServiceLoader} and Jupiter's
 *       discovery both read the thread context classloader; surefire leaves it on the flat app
 *       classpath. The whole launch therefore runs on a DEDICATED thread whose context classloader
 *       is the host's — the membrane between the OSGi world and the bare-JVM world.
 *   <li><b>Engine registration.</b> The engine is registered EXPLICITLY ({@link LauncherConfig},
 *       auto-registration off): {@code ServiceLoader} cannot cross the OSGi boundary (the {@code
 *       META-INF/services} resource lives in the engine bundle, not exposed through package
 *       wiring). The engine {@link Class} is passed by the FRAGMENT — so the fragment's own
 *       bytecode references it and bnd computes the {@code Import-Package} that wires the engine
 *       bundle into the host (the in-container "declare what you import"); and being host-loaded,
 *       its {@code TestEngine} supertype matches the launcher's.
 *   <li><b>Class discovery.</b> Test classes are enumerated via {@link BundleWiring#listResources}
 *       (the OSGi-native way), NOT {@code selectPackage} — whose Jupiter {@code ClasspathScanner}
 *       walks {@code getResources(pkg)} as a file directory and cannot list Felix {@code bundle://}
 *       URLs.
 * </ol>
 *
 * <p>Generic by construction: it names no host type, so it lives in this reusable bundle rather
 * than in each fragment.
 *
 * @param <T> the JUnit {@link TestEngine} the fragment runs against
 */
public final class InContainerJUnitRunner<T extends TestEngine> {

  /** Field separator (ASCII unit separator) in an encoded result line: {@code STATUS␟name␟msg}. */
  public static final String SEP = "\u001F";

  private final ClassLoader hostLoader;
  private final Class<T> engineClass;
  private final String testPackage;

  /**
   * @param hostLoader the host bundle's classloader (a {@link BundleReference}); the fragment
   *     passes {@code SomeFragmentClass.class.getClassLoader()}
   * @param engineClass the {@link TestEngine} to run (e.g. {@code JupiterTestEngine.class}) —
   *     passed by the fragment so its bytecode references the engine class (bnd then imports the
   *     engine package, wiring its bundle into the host); host-loaded, so its supertype matches the
   *     launcher's
   * @param testPackage the package to enumerate for {@code *Test} classes
   */
  public InContainerJUnitRunner(ClassLoader hostLoader, Class<T> engineClass, String testPackage) {
    this.hostLoader = hostLoader;
    this.engineClass = engineClass;
    this.testPackage = testPackage;
  }

  private TestEngine instantiateEngine() {
    try {
      return engineClass.getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("cannot instantiate TestEngine " + engineClass.getName(), e);
    }
  }

  /**
   * Run, on a dedicated thread bound to the host classloader, every {@code @Test} class under the
   * configured package, returning one encoded line per finished test: {@code "PASS" + SEP + name}
   * or {@code "FAIL" + SEP + name + SEP + message}.
   */
  public List<String> run() throws InterruptedException {
    final AtomicReference<List<String>> out = new AtomicReference<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();

    final Thread worker =
        new Thread(
            () -> {
              try {
                out.set(execute());
              } catch (Throwable t) {
                failure.set(t);
              }
            },
            "in-container-junit");
    worker.setContextClassLoader(hostLoader);
    worker.start();
    worker.join();

    if (failure.get() != null) {
      throw new IllegalStateException("in-container JUnit run failed", failure.get());
    }
    return out.get();
  }

  private List<String> execute() {
    final List<String> results = new ArrayList<>();

    final Launcher launcher =
        LauncherFactory.create(
            LauncherConfig.builder()
                .enableTestEngineAutoRegistration(false)
                .addTestEngines(instantiateEngine())
                .build());

    final LauncherDiscoveryRequest request =
        LauncherDiscoveryRequestBuilder.request().selectors(discoverTestClasses()).build();

    launcher.registerTestExecutionListeners(
        new TestExecutionListener() {
          @Override
          public void executionFinished(TestIdentifier id, TestExecutionResult result) {
            if (!id.isTest()) {
              return;
            }
            if (result.getStatus() == TestExecutionResult.Status.SUCCESSFUL) {
              results.add("PASS" + SEP + id.getDisplayName());
            } else {
              final String message =
                  result
                      .getThrowable()
                      .map(InContainerJUnitRunner::describe)
                      .orElse("(no message)");
              results.add("FAIL" + SEP + id.getDisplayName() + SEP + message);
            }
          }
        });

    launcher.execute(request);
    return results;
  }

  /** Flatten a throwable and its whole cause chain to one line + the first frame of each. */
  private static String describe(Throwable t) {
    final StringBuilder chain = new StringBuilder();
    for (Throwable c = t; c != null; c = c.getCause()) {
      chain.append('[').append(c.getClass().getName()).append(": ").append(c.getMessage());
      if (c.getStackTrace().length > 0) {
        chain.append(" @ ").append(c.getStackTrace()[0]);
      }
      chain.append(']');
      if (c.getCause() == c) {
        break;
      }
    }
    return chain.toString();
  }

  /** Enumerate {@code *Test} classes under the configured package via the host bundle's wiring. */
  private List<DiscoverySelector> discoverTestClasses() {
    final String pkgPath = testPackage.replace('.', '/');
    final Bundle host = ((BundleReference) hostLoader).getBundle();
    final BundleWiring wiring = host.adapt(BundleWiring.class);

    final Collection<String> classEntries =
        wiring.listResources(pkgPath, "*Test.class", BundleWiring.LISTRESOURCES_LOCAL);

    final List<DiscoverySelector> selectors = new ArrayList<>();
    for (String entry : classEntries) {
      final String className =
          entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
      selectors.add(DiscoverySelectors.selectClass(className));
    }
    return selectors;
  }
}
