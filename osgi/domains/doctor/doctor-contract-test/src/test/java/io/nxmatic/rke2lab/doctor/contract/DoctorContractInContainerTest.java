package io.nxmatic.rke2lab.doctor.contract;

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
 * The bare-JVM proxy (VSCode-clickable) that runs the doctor-records value-type tests IN-CONTAINER.
 * It boots a real Felix, installs the doctor-contract host bundle, this {@code -test} fragment, and
 * the JUnit bundle world, resolves the host (attaching the fragment, OSGi Core §3.14), then drives
 * a JUnit Platform Launcher FROM INSIDE the framework via {@code DoctorContractTests} — reached
 * reflectively through the host classloader so no in-framework JUnit type crosses into this world.
 *
 * <p>Replaces {@code DoctorPortInContainerTest}: doctor-port was de-seamed (its two interfaces
 * folded into doctor-core), so the record value-type tests + the shared testkit moved to this
 * fragment on the doctor-contract host, where they belong. Each in-container test comes back as an
 * encoded {@link String} mapped to one {@link DynamicTest}, so VSCode shows a node per test.
 */
@OsgiWorld
class DoctorContractInContainerTest {

  private static final String FIXTURE_FILTER = "(&(type=fixture)(suite=doctor)(role=contract))";
  private static final String RUNNER_FQN = "io.nxmatic.rke2lab.doctor.contract.DoctorContractTests";

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      OutOfContainerFrameworkExtension.builder()
          // doctor-contract imports seed.broker.port (its interfaces name SeedEnvelope) — the one
          // true membrane seam, so it is system-exported for doctor-contract to resolve.
          // systemd-contract is DE-SEAMED (an installed bundle): the fixtures name the dbus-tcp
          // unit
          // via SystemdUnitId, pulled bundle-to-bundle by installImportClosureOf, not here.
          // doctor-contract itself + doctor.spi (which FakeSpecialist imports) are likewise derived
          // from the manifests via installImportClosureOf.
          .systemPackages("io.nxmatic.rke2lab.seed.broker.port;version=1.0.0")
          .withJUnitRunner()
          .build();

  @TestFactory
  Stream<DynamicTest> valueTypeTests() throws Exception {
    // Select the doctor-records fixture by what it DECLARES; its host (doctor-records) is found
    // through the fragment's Fragment-Host — neither named by a literal. Seed the import closure
    // with BOTH host and fragment: the fragment's FakeSpecialist imports doctor.spi, which the
    // host's own manifest does not, so the fragment must be walked too.
    final OutOfContainerFrameworkExtension.FixtureWithHost fixture =
        felix.installFixtureWithHost(FIXTURE_FILTER);
    final Bundle host = fixture.host();
    final List<Bundle> toResolve = new ArrayList<>(List.of(host));
    toResolve.addAll(felix.installImportClosureOf(host, fixture.fragment()));
    if (!felix.resolve(toResolve)) {
      fail("doctor-contract host (with its -test fragment) must resolve");
    }
    host.start();

    final Class<?> runner = host.loadClass(RUNNER_FQN);
    final Method run = runner.getMethod("run");
    @SuppressWarnings("unchecked")
    final List<String> results = (List<String>) run.invoke(null);

    if (results.isEmpty()) {
      fail("no in-container test was discovered — the jupiter engine did not attach");
    }

    return results.stream().map(DoctorContractInContainerTest::toDynamicTest);
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
