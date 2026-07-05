package io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container;

import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.JUnitLauncherCore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.osgi.framework.wiring.BundleWiring;

/**
 * The in-framework half of the OSGi test-fragment model — the in-container twin of {@code
 * OutOfContainerFrameworkExtension} (its host-side counterpart). A host {@code -test} fragment,
 * which shares its host bundle's classloader, constructs one of these (reflectively, THROUGH that
 * loader) and calls {@link #run()} so a real JUnit Platform Launcher runs INSIDE the framework,
 * discovering and executing the fragment's {@code @Test} classes white-box (exactly as the host's
 * own code sees them). {@link #run()} returns plain {@link String}s so no JUnit type ever crosses
 * into the calling JVM world.
 *
 * <p>A thin consumer of the neutral {@link JUnitLauncherCore}: the core owns the three OSGi
 * crossings (host-bound worker thread, explicit engine, wiring-based discovery); this envelope
 * supplies the two injected strategies — the {@code *Test} enumeration ({@link
 * BundleWiring#listResources} over the host's wiring) and the PASS/FAIL harvest.
 *
 * <p>An instance, not a static helper: the host classloader, the engine class, and the package to
 * scan are the collaborators it runs against — passed in, held as fields, explicit in the call
 * graph.
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
   * @param hostLoader the host bundle's classloader (a {@code BundleReference}); the fragment
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

  /**
   * Run every {@code @Test} class under the configured package through {@link JUnitLauncherCore},
   * returning one encoded line per finished test: {@code "PASS" + SEP + name} or {@code "FAIL" +
   * SEP + name + SEP + message}.
   */
  public List<String> run() throws InterruptedException {
    return new JUnitLauncherCore<List<String>>()
        .run(hostLoader, engineClass, this::discoverTestClasses, InContainerJUnitRunner::harvest);
  }

  /**
   * Enumerate {@code *Test} classes under the configured package via the host bundle's wiring —
   * required in-container, where Jupiter's file-directory {@code ClasspathScanner} cannot list
   * Felix {@code bundle://} URLs.
   */
  private List<DiscoverySelector> discoverTestClasses(Optional<BundleWiring> wiring) {
    final String pkgPath = testPackage.replace('.', '/');
    final BundleWiring hostWiring =
        wiring.orElseThrow(
            () ->
                new IllegalStateException(
                    "InContainerJUnitRunner requires a bundle-loaded host — no BundleWiring"));

    final List<DiscoverySelector> selectors = new ArrayList<>();
    for (String entry :
        hostWiring.listResources(pkgPath, "*Test.class", BundleWiring.LISTRESOURCES_LOCAL)) {
      final String className =
          entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
      selectors.add(DiscoverySelectors.selectClass(className));
    }
    return selectors;
  }

  /** Harvest one encoded PASS/FAIL line per finished test. */
  private static List<String> harvest(Launcher launcher, LauncherDiscoveryRequest request) {
    final List<String> results = new ArrayList<>();
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
}
