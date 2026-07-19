package io.nxmatic.rke2lab.manifests.bdd;

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
import org.osgi.service.log.LogLevel;

/**
 * The bare-JVM proxy (VSCode-clickable) that runs the manifests scion's in-container proof. It
 * boots a Felix carrying BOTH worlds — the JUnit bundles (via the runner) and the jGiven boot
 * closure (via {@link ScenarioTestkit#felix()}, since the scenario is a jGiven ScenarioTest) —
 * installs the manifests-bdd host bundle and this {@code -test} fragment PLUS manifests-bdd's whole
 * runtime graph (its own import closure pulls manifests-core, the cdk8s carrier + systemd fragment,
 * the sibling ports, the pipeline engine, unitrepo-core, and the third-party OSGi bundles),
 * resolves the host (attaching the fragment, OSGi Core §3.14), then drives {@code
 * ManifestsBddTests} FROM INSIDE the framework. Resolving the host IS the live proof that the
 * scion's graph wires in-container.
 *
 * <p>Unlike the bbox proof, the scenario resolves the REAL synthesis (manifests-core's DS
 * {@code @Component}s), so {@code .withScr()} must run for them to activate. The one collaborator
 * not SCR-published is the {@link io.nxmatic.rke2lab.seed.broker.port.RunGate} — the passenger
 * registers a mock in-container, on the shared fragment loader, so nothing crosses to the host JVM
 * and only {@code seed.broker.port} (the one true host↔OSGi membrane) is system-exported.
 */
@OsgiWorld
// Flip to LogLevel.DEBUG to troubleshoot a failed in-container resolve/activation
// (Felix then traces WHICH requirement could not be wired); WARNING is the quiet committed default.
@FrameworkLog(LogLevel.WARN)
// Felix's own log level so the resolver prints WHICH requirement could not be wired.
class ManifestsBddInContainerTest {

  // Select the fixture by what it declares (type=fixture, suite, role); its host (manifests-bdd)
  // comes from Fragment-Host.
  private static final String BDD_FIXTURE = "(&(type=fixture)(suite=manifests)(role=bdd))";
  private static final String RUNNER_FQN = "io.nxmatic.rke2lab.manifests.bdd.ManifestsBddTests";

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      ScenarioTestkit.felix() // jGiven boot closure (byte-buddy, jgiven-wrap, slf4j/junit packages)
          // manifests-core carries @Components (the synthesis + overlay services), so its bundle
          // Requires the DS extender (osgi.extender=osgi.component); felix.scr must run for them to
          // resolve and activate — the scenario resolves the REAL services, not mocks.
          .withScr()
          // The ONE package still system-exported is seed.broker.port (RunGate + SeedEnvelope — the
          // one true host↔OSGi membrane, published by the host in prod). Everything else
          // (manifests-contract, manifests-core, the cdk8s carrier, seed-broker-codec, jackson,
          // jGiven) is derived from the host bundle's manifest in the test body via
          // installImportClosureOf.
          .systemPackages("io.nxmatic.rke2lab.seed.broker.port;version=1.0.0")
          // The JUnit-Platform runner world (launcher + engine) the front-door drives in-container.
          .withJUnitRunner()
          .build();

  @TestFactory
  Stream<DynamicTest> scionTests() throws Exception {
    // The shared driver installs the fixture + its host (found through the fragment's
    // Fragment-Host),
    // pulls the host's own import closure, resolves+starts it, and runs the front-door
    // in-container.
    return InContainerScenarios.drive(
        felix,
        RUNNER_FQN,
        f -> {
          final Bundle host = f.installFixtureWithHost(BDD_FIXTURE).host();
          final List<Bundle> toResolve = new ArrayList<>(List.of(host));
          toResolve.addAll(f.installImportClosureOf(host));
          return new Provisioning(host, toResolve, true);
        });
  }
}
