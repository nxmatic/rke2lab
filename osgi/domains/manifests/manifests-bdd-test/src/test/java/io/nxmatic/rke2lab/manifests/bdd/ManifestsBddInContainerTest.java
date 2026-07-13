package io.nxmatic.rke2lab.manifests.bdd;

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
 * The bare-JVM proxy (VSCode-clickable) that runs the manifests scion's in-container proof. It
 * boots a Felix carrying BOTH worlds — the JUnit bundles (via the runner) and the jGiven boot
 * closure (via {@link JGivenTestkit#felix()}, since the scenario is a jGiven ScenarioTest) —
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
// To debug a failed in-container resolve/activation, annotate with @FrameworkLog(DEBUG) — it raises
// Felix's own log level so the resolver prints WHICH requirement could not be wired.
class ManifestsBddInContainerTest {

  // Select the fixture by what it declares (type=fixture, suite, role); its host (manifests-bdd)
  // comes from Fragment-Host.
  private static final String BDD_FIXTURE = "(&(type=fixture)(suite=manifests)(role=bdd))";
  private static final String RUNNER_FQN = "io.nxmatic.rke2lab.manifests.bdd.ManifestsBddTests";

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      JGivenTestkit.felix() // jGiven boot closure (byte-buddy, jgiven-wrap, slf4j/junit packages)
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
    // Install the fixture + its host (manifests-bdd), located through the fragment's Fragment-Host
    // —
    // no host named by a literal. Then let OSGi pull the host's own import closure (manifests-core
    // +
    // its cdk8s runtime graph, the sibling ports, the third-party libraries) instead of a hand-kept
    // list. Resolve the host + the derived closure together, in one pass.
    final Bundle host = felix.installFixtureWithHost(BDD_FIXTURE).host();
    final List<Bundle> toResolve = new ArrayList<>(List.of(host));
    toResolve.addAll(felix.installImportClosureOf(host));
    // ssh-to-age-edge is reached by an SCR service reference, not a package import, so the import
    // closure does not pull it — yet DefaultManifestSynthesisService has a MANDATORY @Reference
    // SshToAgeConverter and will not activate without its provider. Install it explicitly so the
    // synthesis component publishes its service in-container. (The other mandatory @Reference, the
    // Resolver service, is provided by the testkit's withResolver() default — no manual install.)
    toResolve.add(felix.install("io.nxmatic.rke2lab.sshtoage.edge"));
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
      fail("manifests-bdd (with its -test fragment + synthesis graph) must resolve" + states);
    }
    // Start the whole graph (the shared install/start gesture prod's launcher runs on each bundle):
    // SCR only activates @Components of ACTIVE bundles, and this scenario resolves the REAL
    // synthesis published by manifests-core (a sibling bundle), not a mock — so unlike the contact
    // scions it needs its whole graph started, not merely resolved. felix.resolver (the Resolver
    // service the synthesis references) is already installed+started by the testkit's
    // withResolver()
    // default, before this graph.
    felix.startAll(toResolve);
    host.start();

    final Class<?> runner = host.loadClass(RUNNER_FQN);
    final Method run = runner.getMethod("run");
    @SuppressWarnings("unchecked")
    final List<String> results = (List<String>) run.invoke(null);

    if (results.isEmpty()) {
      fail("no in-container test was discovered — the jupiter engine did not attach");
    }

    return results.stream().map(ManifestsBddInContainerTest::toDynamicTest);
  }

  private static DynamicTest toDynamicTest(String encoded) {
    final String[] parts = encoded.split("", 3);
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
