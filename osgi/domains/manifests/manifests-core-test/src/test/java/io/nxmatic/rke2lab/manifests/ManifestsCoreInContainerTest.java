package io.nxmatic.rke2lab.manifests;

import static org.junit.jupiter.api.Assertions.fail;

import io.nxmatic.rke2lab.jgiven.testkit.JGivenTestkit;
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
 * The bare-JVM proxy (VSCode-clickable) that runs manifests-core's actor tests IN-CONTAINER. It
 * boots a Felix carrying the JUnit + jGiven worlds (via {@link JGivenTestkit#felix()}), installs
 * the manifests-core host bundle and this {@code -test} fragment plus manifests-core's whole
 * runtime graph — the cdk8s carrier (with its embedded flat closure) and the
 * systemd-cdk8s-manifests fragment that rides it, the sibling ports, the pipeline engine,
 * unitrepo-core, and the third-party OSGi bundles — resolves the host (attaching the fragment, OSGi
 * Core §3.14), then drives a JUnit Platform Launcher FROM INSIDE the framework via {@code
 * ManifestsCoreTests}. Resolving the host IS the live proof that the cdk8s bundle-to-bundle wiring
 * (org.cdk8s/software.constructs exported by the carrier, no host-flat leak) holds in-container.
 *
 * <p>Each in-container test comes back as an encoded {@link String} mapped to one {@link
 * DynamicTest}, so VSCode shows a node per test and a single failure fails alone.
 */
@OsgiWorld
// To debug a failed in-container resolve/activation, annotate this class with @FrameworkLog(DEBUG)
// (io.nxmatic.rke2lab.osgi.runtime.scenario.engine.FrameworkLog) — it raises Felix's own
// felix.log.level so
// the
// resolver prints WHICH requirement could not be wired to System.out (resolve() otherwise returns a
// bare false). Left as a comment: it is the lever to reach for, not a permanent dependency.
//   @FrameworkLog(FrameworkLog.Level.DEBUG)
class ManifestsCoreInContainerTest {

  // Select the fixture by what it declares (type=fixture, suite, role); its host (manifests-core)
  // comes from Fragment-Host.
  private static final String CORE_FIXTURE = "(&(type=fixture)(suite=manifests)(role=core))";
  private static final String RUNNER_FQN = "io.nxmatic.rke2lab.manifests.ManifestsCoreTests";

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      JGivenTestkit.felix() // jGiven boot closure (byte-buddy, jgiven-wrap, slf4j/junit packages)
          // manifests-core carries @Components (YamlMapper, the Default…Services), so its bundle
          // Requires the DS extender (osgi.extender=osgi.component); felix.scr must run for
          // manifests-core to resolve and activate.
          .withScr()
          // The JUnit runner world — the proxy's own infrastructure, the single shared declaration.
          .withJUnitRunner()
          // The sibling domain PORTS manifests-core imports are seams (type=seam): host-flat,
          // system-exported here exactly as in prod, never installed as bundles. The closure walk
          // skips seam exporters, so these would never be pulled — they belong here, not in the
          // derived set. Everything else manifests-core's runtime graph needs (the cdk8s carrier
          // and
          // its systemd fragment, unitrepo-core, jackson, ipaddress, snakeyaml, commons-compress)
          // is
          // derived from the host's manifest in the test body via installImportClosureOf.
          .systemPackages(
              "io.nxmatic.rke2lab.manifests.port;version=1.0.0",
              "io.nxmatic.rke2lab.manifests.port.node;version=1.0.0",
              "io.nxmatic.rke2lab.manifests.port.profiles;version=1.0.0",
              "io.nxmatic.rke2lab.netplan.port;version=1.0.0",
              "io.nxmatic.rke2lab.systemd.port;version=1.0.0",
              "io.nxmatic.rke2lab.pipeline;version=1.0.0")
          .build();

  @TestFactory
  Stream<DynamicTest> actorTests() throws Exception {
    // Install the manifests-core host + its -test fragment (both selected by what they declare, the
    // fragment located through its Fragment-Host), then let OSGi pull the host's whole runtime
    // graph
    // from its manifest — the cdk8s carrier (and the systemd-cdk8s-manifests fragment that exports
    // a
    // package the host imports, so it is reached as an exporter), unitrepo-core, jackson,
    // ipaddress,
    // snakeyaml, commons-compress — instead of a hand-kept list. None started.
    Bundle host = felix.installFixtureWithHost(CORE_FIXTURE).host();
    final List<Bundle> toResolve = new ArrayList<>(List.of(host));
    toResolve.addAll(felix.installImportClosureOf(host));
    // Resolve the entire set at once: the resolver wires host ⇄ carrier ⇄ jackson ⇄ ports in one
    // pass, and attaches the systemd fragment to the carrier (OSGi Core §3.14). Order-independent.
    if (!felix.resolve(toResolve)) {
      final StringBuilder states = new StringBuilder();
      for (Bundle b : toResolve) {
        if ((b.getState() & Bundle.RESOLVED) == 0) {
          states
              .append("\n  UNRESOLVED ")
              .append(b.getSymbolicName())
              .append(" [")
              .append(b.getBundleId())
              .append("]");
        }
      }
      fail(
          "manifests-core and its runtime graph (cdk8s carrier + fragments) must resolve" + states);
    }
    host.start();

    final Class<?> runner = host.loadClass(RUNNER_FQN);
    final Method run = runner.getMethod("run");
    @SuppressWarnings("unchecked")
    final List<String> results = (List<String>) run.invoke(null);

    if (results.isEmpty()) {
      fail("no in-container test was discovered — the jupiter engine did not attach");
    }

    return results.stream().map(ManifestsCoreInContainerTest::toDynamicTest);
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
