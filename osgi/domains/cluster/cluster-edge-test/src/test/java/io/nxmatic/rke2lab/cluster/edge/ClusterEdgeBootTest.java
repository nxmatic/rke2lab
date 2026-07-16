package io.nxmatic.rke2lab.cluster.edge;

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

/**
 * The bare-JVM proxy (VSCode-clickable) that runs the cluster edge's in-container proof. It boots a
 * Felix with SCR + the JUnit runner world, installs the cluster-edge host bundle and this {@code
 * -test} fragment plus the edge's import closure (cluster-contract among it), resolves the host
 * (attaching the fragment, OSGi Core §3.14), then drives {@code ClusterEdgeTests} FROM INSIDE the
 * framework. The passenger it runs resolves the SCR-published contact in the edge's own realm and
 * calls it typed.
 *
 * <p>No domain {@code systemPackages}: cluster-contract is DE-SEAMED (an installed bundle), wired
 * bundle-to-bundle by {@code installImportClosureOf}. The passenger observes the contact
 * IN-CONTAINER (not out-of-container via a host-loaded Class) — so nothing of cluster.contract
 * crosses to the host JVM, and the test posture matches the live boot posture. Replaces the former
 * out-of-container ClusterEdgeBootTest that system-exported cluster.contract to read the contact
 * typed.
 */
@OsgiWorld
// Flip to FrameworkLog.Level.DEBUG to troubleshoot a failed in-container resolve/activation
// (Felix then traces WHICH requirement could not be wired); WARNING is the quiet committed default.
@FrameworkLog(FrameworkLog.Level.WARNING)
class ClusterEdgeBootTest {

  // The cluster edge fixture-test fragment, selected by what it declares; its host cluster-edge is
  // found through the fragment's Fragment-Host — no host named by a literal here.
  private static final String EDGE_FIXTURE = "(&(type=fixture)(suite=cluster)(role=edge))";
  private static final String RUNNER_FQN = "io.nxmatic.rke2lab.cluster.edge.ClusterEdgeTests";

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      OutOfContainerFrameworkExtension.builder()
          // SCR (default) publishes the edge's @Component; org.slf4j (default export) satisfies its
          // log import. NO domain systemPackages — cluster.contract is DE-SEAMED, pulled as an
          // installed bundle by installImportClosureOf.
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
