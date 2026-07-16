package io.nxmatic.rke2lab.doctor.contract;

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
// Flip to FrameworkLog.Level.DEBUG to troubleshoot a failed in-container resolve/activation
// (Felix then traces WHICH requirement could not be wired); WARNING is the quiet committed default.
@FrameworkLog(FrameworkLog.Level.WARNING)
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
    // with
    // BOTH host and fragment: the fragment's FakeSpecialist imports doctor.spi, which the host's
    // own
    // manifest does not, so the fragment must be walked too.
    return InContainerScenarios.drive(
        felix,
        RUNNER_FQN,
        f -> {
          final OutOfContainerFrameworkExtension.FixtureWithHost fixture =
              f.installFixtureWithHost(FIXTURE_FILTER);
          final Bundle host = fixture.host();
          final List<Bundle> toResolve = new ArrayList<>(List.of(host));
          toResolve.addAll(f.installImportClosureOf(host, fixture.fragment()));
          return new Provisioning(host, toResolve, false);
        });
  }
}
