package io.seedmatic.rke2lab.doctor;

import io.seedmatic.rke2lab.junit.testkit.OsgiWorld;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.FrameworkLog;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.InContainerScenarios;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.InContainerScenarios.Provisioning;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.OutOfContainerFrameworkExtension;
import io.seedmatic.rke2lab.scenario.testkit.ScenarioTestkit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.framework.Bundle;
import org.osgi.service.log.LogLevel;

/**
 * The bare-JVM proxy (VSCode-clickable) that runs doctor-core's actor tests IN-CONTAINER. It boots
 * a Felix carrying BOTH worlds — the JUnit bundles (via the runner) and the jGiven boot closure
 * (via {@link ScenarioTestkit#felix()}, for the 2 scenario tests) — installs the doctor-core host
 * bundle and this {@code -test} fragment, resolves the host (attaching the fragment, OSGi Core
 * §3.14), then drives a JUnit Platform Launcher FROM INSIDE the framework via {@code
 * DoctorCoreTests}. The actor tests read doctor-core's sealed package-private actors white-box; the
 * jGiven scenarios prove BDD plays in-container for doctor.
 *
 * <p>Each in-container test comes back as an encoded {@link String} mapped to one {@link
 * DynamicTest}, so VSCode shows a node per test and a single failure fails alone.
 */
@OsgiWorld
// Flip to LogLevel.DEBUG to troubleshoot a failed in-container resolve/activation
// (Felix then traces WHICH requirement could not be wired); WARNING is the quiet committed default.
@FrameworkLog(LogLevel.WARN)
class DoctorCoreInContainerTest {

  // The doctor suite's two fixtures, selected by what they declare. role=core is the host under
  // test (its actors); role=contract contributes the shared doctor.testkit fixtures package its
  // sibling imports. Each fragment names its own host (doctor-core / doctor-contract) via
  // Fragment-Host, so no host is named by a literal here.
  private static final String CORE_FIXTURE = "(&(type=fixture)(suite=doctor)(role=core))";
  private static final String CONTRACT_FIXTURE = "(&(type=fixture)(suite=doctor)(role=contract))";
  private static final String RUNNER_FQN = "io.seedmatic.rke2lab.doctor.DoctorCoreTests";

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      ScenarioTestkit.felix() // jGiven boot closure (byte-buddy, jgiven-wrap, slf4j/junit packages)
          // doctor-core now carries the DefaultHealthSystem @Component, so its bundle Requires the
          // DS
          // extender (osgi.extender=osgi.component); felix.scr must run for doctor-core to resolve.
          .withScr()
          // doctor-core's DefaultReadinessAuthority @Component (a SeedHandler) crosses the
          // seed-broker boundary, so the host imports the seed-broker port (SeedEnvelope +
          // SeedHandler); it is the one true membrane seam. systemd-contract is DE-SEAMED (an
          // installed bundle) — doctor-core's dbus-tcp specialist names SystemdUnitId, pulled
          // bundle-to-bundle by installImportClosureOf below, not system-exported here.
          .systemPackages("io.seedmatic.rke2lab.seed.broker.port;version=1.0.0")
          // The JUnit runner world (launcher + engine + this testkit) — the proxy's own
          // infrastructure, the single shared declaration. Everything the HOST declares it needs
          // (doctor.records, doctor.spi, jackson) is derived from its manifest in the test body via
          // installImportClosureOf — no hand-kept list.
          .withJUnitRunner()
          .build();

  @TestFactory
  Stream<DynamicTest> actorTests() throws Exception {
    // doctor-contract carries the value vocabulary + the exported doctor.testkit fixtures (via its
    // own -test fragment); doctor-core depends on it. Each fixture installs its host + fragment,
    // located through the fragment's declared Fragment-Host. Walk the CORE fragment too: its
    // passenger imports systemd.contract (the dbus-tcp specialist's typed unit id), a DE-SEAMED
    // installed bundle — so the closure must see the fragment's imports to pull it.
    return InContainerScenarios.drive(
        felix,
        RUNNER_FQN,
        f -> {
          final Bundle contract = f.installFixtureWithHost(CONTRACT_FIXTURE).host();
          final OutOfContainerFrameworkExtension.FixtureWithHost core =
              f.installFixtureWithHost(CORE_FIXTURE);
          final Bundle host = core.host();
          final List<Bundle> toResolve = new ArrayList<>(List.of(contract, host));
          toResolve.addAll(f.installImportClosureOf(contract, host, core.fragment()));
          return new Provisioning(host, toResolve, false);
        });
  }
}
