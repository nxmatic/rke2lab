package io.nxmatic.rke2lab.dbus.systemd.edge;

import static org.junit.jupiter.api.Assertions.fail;

import io.nxmatic.rke2lab.junit.testkit.OsgiWorld;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OutOfContainerFrameworkExtension;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.framework.Bundle;

/**
 * The bare-JVM proxy (VSCode-clickable) that runs the dbus-systemd edge's in-container proof. It
 * boots a Felix with SCR + the JUnit runner world, installs the dbus-systemd-edge host bundle and
 * this {@code -test} fragment plus the edge's import closure (systemd-contract among it), resolves
 * the host (attaching the fragment, OSGi Core §3.14), then drives {@code DbusSystemdEdgeTests} FROM
 * INSIDE the framework. The passenger it runs resolves the SCR-published probe in the edge's own
 * realm and calls it typed — which also runs the dbus-java transport ServiceLoader inside the
 * edge's Bundle-ClassPath, the load-bearing proof.
 *
 * <p>No domain {@code systemPackages}: systemd-contract is DE-SEAMED (an installed bundle), wired
 * bundle-to-bundle by {@code installImportClosureOf}. The passenger observes the probe IN-CONTAINER
 * (not out-of-container via a host-loaded Class) — so nothing of systemd.contract crosses to the
 * host JVM, and the test posture matches the live boot posture. Replaces the former
 * out-of-container DbusSystemdEdgeBootTest that system-exported systemd.contract to read the probe
 * typed.
 */
@OsgiWorld
class DbusSystemdEdgeBootTest {

  // The dbus-systemd edge fixture-test fragment, selected by what it declares; its host
  // dbus-systemd-edge is found through the fragment's Fragment-Host — no host named by a literal.
  private static final String EDGE_FIXTURE = "(&(type=fixture)(suite=systemd)(role=dbus-edge))";
  private static final String RUNNER_FQN =
      "io.nxmatic.rke2lab.dbus.systemd.edge.DbusSystemdEdgeTests";

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      OutOfContainerFrameworkExtension.builder()
          // SCR (default) publishes the edge's @Component; org.slf4j (default export) satisfies its
          // log import. NO domain systemPackages — systemd.contract is DE-SEAMED, pulled as an
          // installed bundle by installImportClosureOf.
          .withJUnitRunner()
          .build();

  @TestFactory
  Stream<DynamicTest> edgeTests() throws Exception {
    // Install the fixture + its host (dbus-systemd-edge), located through the fragment's
    // Fragment-Host, then pull the host's own import closure (systemd-contract + slf4j + its
    // embedded transport jars ride the host bundle itself) instead of a hand-kept list.
    final Bundle host = felix.installFixtureWithHost(EDGE_FIXTURE).host();
    final List<Bundle> toResolve = new ArrayList<>(List.of(host));
    toResolve.addAll(felix.installImportClosureOf(host));
    if (!felix.resolve(toResolve)) {
      final StringBuilder states = new StringBuilder();
      for (Bundle b : toResolve) {
        if ((b.getState() & Bundle.RESOLVED) == 0) {
          states.append("\n  UNRESOLVED ").append(b.getSymbolicName());
        }
      }
      fail("dbus-systemd-edge (with its -test fragment) must resolve" + states);
    }
    host.start();

    final Class<?> runner = host.loadClass(RUNNER_FQN);
    final Method run = runner.getMethod("run");
    @SuppressWarnings("unchecked")
    final List<String> results = (List<String>) run.invoke(null);

    if (results.isEmpty()) {
      fail("no in-container test was discovered — the jupiter engine did not attach");
    }

    return results.stream().map(DbusSystemdEdgeBootTest::toDynamicTest);
  }

  private static DynamicTest toDynamicTest(String encoded) {
    final String[] parts = encoded.split("\u001F", 3);
    final String status = parts[0];
    final String displayName = parts.length > 1 ? parts[1] : "(unnamed)";
    return DynamicTest.dynamicTest(
        displayName,
        () -> {
          if ("FAIL".equals(status)) {
            fail(parts.length > 2 ? parts[2] : "in-container test failed");
          }
        });
  }
}
