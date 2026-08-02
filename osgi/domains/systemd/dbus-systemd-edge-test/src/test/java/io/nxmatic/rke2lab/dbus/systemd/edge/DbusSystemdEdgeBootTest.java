package io.nxmatic.rke2lab.dbus.systemd.edge;

import io.nxmatic.rke2lab.junit.testkit.OsgiWorld;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.FrameworkLog;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.InContainerScenarios;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.InContainerScenarios.Provisioning;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OutOfContainerFrameworkExtension;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.framework.Bundle;
import org.osgi.service.log.LogLevel;

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
// Flip to LogLevel.DEBUG to troubleshoot a failed in-container resolve/activation
// (Felix then traces WHICH requirement could not be wired); WARNING is the quiet committed default.
@FrameworkLog(LogLevel.WARN)
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
          // log import. No domain systemPackages — systemd.contract is DE-SEAMED, pulled as an
          // installed bundle by installImportClosureOf. But seed.broker.port is the one true
          // host↔OSGi seam (published by the host in prod), so it must be system-exported here as
          // in every other in-container test — systemd.contract imports it, and without the seam
          // the contract (and the edge that imports it) stay UNRESOLVED.
          .systemPackages("io.nxmatic.rke2lab.seed.broker.port;version=1.0.0")
          .withJUnitRunner()
          .build();

  @TestFactory
  Stream<DynamicTest> edgeTests() throws Exception {
    // The shared driver installs the fixture + its host (found through the fragment's
    // Fragment-Host),
    // pulls the host's own import closure, resolves+starts it, and runs the front-door
    // in-container.
    return InContainerScenarios.drive(
        felix,
        RUNNER_FQN,
        f -> {
          final Bundle host = f.installFixtureWithHost(EDGE_FIXTURE).host();
          final List<Bundle> toResolve = new ArrayList<>(List.of(host));
          toResolve.addAll(f.installImportClosureOf(host));
          return new Provisioning(host, toResolve, false);
        });
  }
}
