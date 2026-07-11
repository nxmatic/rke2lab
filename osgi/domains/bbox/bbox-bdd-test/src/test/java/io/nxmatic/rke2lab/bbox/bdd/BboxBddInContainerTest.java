package io.nxmatic.rke2lab.bbox.bdd;

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
 * The bare-JVM proxy (VSCode-clickable) that runs the bbox scion's in-container proof. It boots a
 * Felix carrying BOTH worlds — the JUnit bundles (via the runner) and the jGiven boot closure (via
 * {@link JGivenTestkit#felix()}, since the scenario is a jGiven ScenarioTest) — installs the
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
class BboxBddInContainerTest {

  // The bbox scion fixture, selected by what it declares (its host bbox-bdd is found through the
  // fragment's Fragment-Host — no host named by a literal here).
  private static final String BBOX_BDD_FIXTURE = "(&(type=fixture)(suite=bbox)(role=bdd))";
  private static final String RUNNER_FQN = "io.nxmatic.rke2lab.bbox.bdd.BboxBddTests";

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      JGivenTestkit.felix() // jGiven boot closure (byte-buddy, jgiven-wrap, slf4j/junit packages)
          // bbox is DE-SEAMED: bbox-record + bbox-core are installed bundles (type=record/model),
          // wired bundle-to-bundle by installImportClosureOf — no bbox seam to system-export. What
          // REMAINS system-exported here is the ports still declared type=seam that this scion
          // imports: netplan.port (bbox-core derives the blueprint through it), doctor.port (the
          // scion consults the doctor) and seed.broker.port (RunGate + SeedEnvelope — the one true
          // host↔OSGi membrane, published by the host in prod). netplan.port + doctor.port are the
          // NEXT de-seam targets (same reason bbox was); seed.broker.port stays a seam.
          .systemPackages(
              "io.nxmatic.rke2lab.netplan.port;version=1.0.0",
              "io.nxmatic.rke2lab.doctor.port;version=1.0.0",
              "io.nxmatic.rke2lab.seed.broker.port;version=1.0.0")
          // The JUnit-Platform runner world (launcher + engine) the front-door drives in-container.
          // Everything else (bbox-record, bbox-core, seed-broker-codec, jackson, jGiven) is derived
          // from the host bundle's manifest in the test body via installImportClosureOf.
          .withJUnitRunner()
          .build();

  @TestFactory
  Stream<DynamicTest> scionTests() throws Exception {
    // Install the fixture + its host (bbox-bdd), located through the fragment's Fragment-Host — no
    // host named by a literal. Then let OSGi pull the host's own import closure (its sibling domain
    // bundles + the third-party libraries it imports) instead of a hand-kept list. Resolve the host
    // + the derived closure together, in one pass.
    final Bundle host = felix.installFixtureWithHost(BBOX_BDD_FIXTURE).host();
    final List<Bundle> toResolve = new ArrayList<>(List.of(host));
    toResolve.addAll(felix.installImportClosureOf(host));
    if (!felix.resolve(toResolve)) {
      fail("bbox-bdd (with its -test fragment) must resolve");
    }
    host.start();

    final Class<?> runner = host.loadClass(RUNNER_FQN);
    final Method run = runner.getMethod("run");
    @SuppressWarnings("unchecked")
    final List<String> results = (List<String>) run.invoke(null);

    if (results.isEmpty()) {
      fail("no in-container test was discovered — the jupiter engine did not attach");
    }

    return results.stream().map(BboxBddInContainerTest::toDynamicTest);
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
