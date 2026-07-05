package io.nxmatic.rke2lab.jgiven.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.junit.testkit.OsgiWorld;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OutOfContainerFrameworkExtension;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.framework.Bundle;

/**
 * The testkit's own non-regression guard — the palier-2/3 proof from the jgiven-osgi-bundle spike,
 * promoted from throwaway to a lasting guard ({@code @OsgiWorld}, default reactor). It asserts the
 * two reusable assets STILL work as shipped:
 *
 * <ul>
 *   <li>{@link JGivenTestkit#felix()} boots a Felix where {@code jgiven-wrap} reaches ACTIVE
 *       (palier 2) — the boot closure resolves.
 *   <li>the {@code jgiven-probe} host + its {@code jgiven-probe-test} fragment (whose manifest bnd
 *       computed from the shared {@code jgiven-fragment.bnd} include) run a full Given/When/Then
 *       IN-CONTAINER through the host classloader, white-box-reading a package-private field
 *       (palier 3) — the fragment shape + the forced import STILL produce a working fragment.
 * </ul>
 *
 * <p>If the shared include drops the forced {@code com.tngtech.jgiven.impl.intercept} import, or
 * the boot helper loses a bundle, palier 3 goes red here — the guard catches a regression in the
 * template a host like doctor relies on.
 */
@OsgiWorld
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JGivenTestkitGuardTest {

  private static final String FIXTURE_FILTER = "(&(type=fixture)(suite=jgiven)(role=probe))";
  private static final String RUNNER_FQN = "io.nxmatic.rke2lab.jgiven.probe.VaultScenarioRunner";

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix = JGivenTestkit.felix().build();

  /** Palier 2 — the wrap bundle's import closure resolves and it reaches ACTIVE. */
  @Test
  @Order(2)
  void palier2_wrapBundleReachesActive() {
    Bundle wrap = felix.bundle(JGivenTestkit.WRAP_BSN);
    assertEquals(
        Bundle.ACTIVE,
        wrap.getState(),
        "jgiven-wrap must reach ACTIVE — its whole import closure resolved against the dependency "
            + "bundles, with no jgiven package system-exported");
  }

  /**
   * Palier 3 — install the pure host POJO bundle and its {@code -test} fragment, attach the
   * fragment by resolving the host, then run the jGiven scenario through the host classloader. The
   * fragment contributed the jGiven + byte-buddy imports the host never declared (via the shared
   * include); byte-buddy injects the stage proxy into the host loader. A returned {@code "OK"}
   * means the full Given/When/Then ran in-container, including the white-box read of the host
   * POJO's package-private field.
   */
  @Test
  @Order(3)
  void palier3_scenarioRunsInContainerViaFragment() throws Exception {
    // Select the probe fixture by what it DECLARES; its host (jgiven-probe) is found through the
    // fragment's Fragment-Host, so neither is named by a Bundle-SymbolicName literal. Both
    // installed
    // WITHOUT starting: a fragment cannot be started, and the host is resolved only AFTER the
    // fragment is present so the framework attaches it (OSGi Core §3.14).
    OutOfContainerFrameworkExtension.FixtureWithHost fixture =
        felix.installFixtureWithHost(FIXTURE_FILTER);
    Bundle host = fixture.host();
    Bundle fragment = fixture.fragment();

    // Resolve the host: the framework folds the fragment's Import-Package into the host's and
    // resolves the merged set as one, attaching the fragment.
    boolean resolved = felix.resolve(List.of(host));
    assertTrue(resolved, "host bundle (with fragment) must resolve");
    assertEquals(
        Bundle.RESOLVED,
        fragment.getState(),
        "fragment must reach RESOLVED — i.e. it attached to the host (a fragment never goes ACTIVE)");

    host.start();
    assertEquals(Bundle.ACTIVE, host.getState(), "host bundle must reach ACTIVE");

    // Run the scenario reflectively THROUGH THE HOST LOADER. The runner lives in the fragment but
    // loads via the host (fragments share the host classloader); it returns a plain String, so no
    // jgiven type ever crosses into this test's app world.
    Class<?> runner = host.loadClass(RUNNER_FQN);
    Method run = runner.getMethod("run");
    Object result = run.invoke(null);

    assertEquals(
        "OK",
        result,
        "the jgiven Given/When/Then must run in-container (byte-buddy stage proxy injected into the "
            + "host loader, white-box read of the package-private balance)");
  }
}
