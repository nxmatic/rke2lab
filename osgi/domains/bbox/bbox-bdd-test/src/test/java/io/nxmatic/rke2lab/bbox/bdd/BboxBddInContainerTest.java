package io.nxmatic.rke2lab.bbox.bdd;

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
 * The bare-JVM proxy (VSCode-clickable) that runs the bbox scion's in-container proof. It boots a
 * Felix carrying BOTH worlds — the JUnit bundles (via the runner) and the jGiven boot closure (via
 * {@link ScenarioTestkit#felix()}, since the scenario is a jGiven ScenarioTest) — installs the
 * bbox-bdd host bundle and this {@code -test} fragment, resolves the host (attaching the fragment,
 * OSGi Core §3.14), then drives {@code BboxBddTests} FROM INSIDE the framework. The passenger it
 * runs registers its mock collaborators in-container and plays the scenario through the front-door.
 *
 * <p>No {@code systemPackages} seam list: the scenario resolves its collaborators from the registry
 * IN-CONTAINER, and the passenger registers the mocks on the shared bundle loader (the fragment) —
 * nothing crosses to the host JVM, so no port needs system-exporting. This is the in-container
 * scion pattern, the twin of {@code DoctorCoreInContainerTest}.
 *
 * <p>Each in-container test comes back as an encoded {@link String} mapped to one {@link
 * DynamicTest}, so VSCode shows a node per test and a single failure fails alone.
 */
@OsgiWorld
// Flip to LogLevel.DEBUG to troubleshoot a failed in-container resolve/activation
// (Felix then traces WHICH requirement could not be wired); WARNING is the quiet committed default.
@FrameworkLog(LogLevel.WARN)
class BboxBddInContainerTest {

  // The bbox scion fixture, selected by what it declares (its host bbox-bdd is found through the
  // fragment's Fragment-Host — no host named by a literal here).
  private static final String BBOX_BDD_FIXTURE = "(&(type=fixture)(suite=bbox)(role=bdd))";
  private static final String RUNNER_FQN = "io.nxmatic.rke2lab.bbox.bdd.BboxBddTests";

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      ScenarioTestkit.felix() // jGiven boot closure (byte-buddy, jgiven-wrap, slf4j/junit packages)
          // bbox, netplan and doctor are all DE-SEAMED: their contracts are installed bundles
          // (type=contract/record/model), wired bundle-to-bundle by installImportClosureOf — no
          // domain seam to system-export. The ONE package still system-exported is seed.broker.port
          // (RunGate + SeedEnvelope — the one true host↔OSGi membrane, published by the host in
          // prod). netplan.contract (bbox-core derives the blueprint through it) and
          // doctor.contract
          // (the scion consults the doctor) are pulled through the host's import closure.
          .systemPackages("io.nxmatic.rke2lab.seed.broker.port;version=1.0.0")
          // The JUnit-Platform runner world (launcher + engine) the front-door drives in-container.
          // Everything else (bbox-contract, bbox-core, seed-broker-codec, jackson, jGiven) is
          // derived
          // from the host bundle's manifest in the test body via installImportClosureOf.
          .withJUnitRunner()
          .build();

  @TestFactory
  Stream<DynamicTest> scionTests() throws Exception {
    // Install the fixture + its host (bbox-bdd), located through the fragment's Fragment-Host — no
    // host named by a literal. Then let OSGi pull the host's own import closure (its sibling domain
    // bundles + the third-party libraries it imports) instead of a hand-kept list. The shared
    // driver
    // resolves the host + the derived closure, starts it, and runs the front-door in-container.
    return InContainerScenarios.drive(
        felix,
        RUNNER_FQN,
        f -> {
          final Bundle host = f.installFixtureWithHost(BBOX_BDD_FIXTURE).host();
          final List<Bundle> toResolve = new ArrayList<>(List.of(host));
          toResolve.addAll(f.installImportClosureOf(host));
          return new Provisioning(host, toResolve, false);
        });
  }
}
