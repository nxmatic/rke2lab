package io.nxmatic.rke2lab.manifests;

import io.nxmatic.rke2lab.junit.testkit.OsgiWorld;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.FrameworkLog;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.InContainerScenarios;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.InContainerScenarios.Provisioning;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OutOfContainerFrameworkExtension;
import io.nxmatic.rke2lab.scenario.testkit.ScenarioTestkit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.framework.Bundle;

/**
 * The bare-JVM proxy (VSCode-clickable) that runs manifests-core's actor tests IN-CONTAINER. It
 * boots a Felix carrying the JUnit + jGiven worlds (via {@link ScenarioTestkit#felix()}), installs
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
// Flip to FrameworkLog.Level.DEBUG to troubleshoot a failed in-container resolve/activation
// (Felix then traces WHICH requirement could not be wired); WARNING is the quiet committed default.
@FrameworkLog(FrameworkLog.Level.WARNING)
// (io.nxmatic.rke2lab.osgi.runtime.scenario.engine.FrameworkLog) — it raises Felix's own
// felix.log.level so
// the
// resolver prints WHICH requirement could not be wired to System.out (resolve() otherwise returns a
// bare false). Left as a comment: it is the lever to reach for, not a permanent dependency.
class ManifestsCoreInContainerTest {

  // Select the fixture by what it declares (type=fixture, suite, role); its host (manifests-core)
  // comes from Fragment-Host.
  private static final String CORE_FIXTURE = "(&(type=fixture)(suite=manifests)(role=core))";
  private static final String RUNNER_FQN = "io.nxmatic.rke2lab.manifests.ManifestsCoreTests";

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      ScenarioTestkit.felix() // jGiven boot closure (byte-buddy, jgiven-wrap, slf4j/junit packages)
          // manifests-core carries @Components (YamlMapper, the Default…Services), so its bundle
          // Requires the DS extender (osgi.extender=osgi.component); felix.scr must run for
          // manifests-core to resolve and activate.
          .withScr()
          // The JUnit runner world — the proxy's own infrastructure, the single shared declaration.
          .withJUnitRunner()
          // No systemPackages: the synthesis grammar is now internal to manifests-core (package
          // io.nxmatic.rke2lab.manifests.internal.synthesis, un-exported), not a seam. Everything
          // manifests-core's runtime graph needs — manifests-contract (a DE-SEAMED installed
          // bundle), the cdk8s carrier and its systemd fragment, unitrepo-core, jackson, ipaddress,
          // snakeyaml, commons-compress — is derived from the host's manifest in the test body via
          // installImportClosureOf.
          .build();

  @TestFactory
  Stream<DynamicTest> actorTests() throws Exception {
    // The shared driver installs the fixture + its host (found through the fragment's
    // Fragment-Host),
    // pulls the host's own import closure, resolves+starts it, and runs the front-door
    // in-container.
    return InContainerScenarios.drive(
        felix,
        RUNNER_FQN,
        f -> {
          final Bundle host = f.installFixtureWithHost(CORE_FIXTURE).host();
          final List<Bundle> toResolve = new ArrayList<>(List.of(host));
          toResolve.addAll(f.installImportClosureOf(host));
          return new Provisioning(host, toResolve, false);
        });
  }
}
