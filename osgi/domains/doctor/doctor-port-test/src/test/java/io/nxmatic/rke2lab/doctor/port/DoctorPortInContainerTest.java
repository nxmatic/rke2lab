package io.nxmatic.rke2lab.doctor.port;

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
 * The bare-JVM proxy (VSCode-clickable) that runs the doctor-port value-type tests IN-CONTAINER. It
 * boots a real Felix, installs the doctor-port host bundle, this {@code -test} fragment, and the
 * whole JUnit bundle world, resolves the host (attaching the fragment, OSGi Core §3.14), then
 * drives a JUnit Platform Launcher FROM INSIDE the framework via {@code DoctorPortTests} (the
 * fragment's host-package entry point onto the generic runner) — reached reflectively through the
 * host classloader so no in-framework jUnit type crosses into this world.
 *
 * <p>Each in-container test comes back as an encoded {@link String}; this factory maps it to one
 * {@link DynamicTest}, so VSCode shows a node per test and a single failing test fails alone. The
 * value-type tests run white-box against doctor-port's own world — the coherence the flat classpath
 * could not give (it is what produced the {@code ReferralReplies} Maven cycle).
 */
@OsgiWorld
class DoctorPortInContainerTest {

  private static final String FIXTURE_FILTER = "(&(type=fixture)(suite=doctor)(role=port))";
  private static final String RUNNER_FQN = "io.nxmatic.rke2lab.doctor.port.DoctorPortTests";

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      OutOfContainerFrameworkExtension.builder()
          // The fragment names the dbus-tcp unit via the typed SystemdUnitId, so it imports the
          // systemd domain's port; and doctor-port now declares consult(Document), so the host
          // imports the gateway port too. Both are seams (system-exported live), so they are
          // system-exported here for the host to resolve the merged-in fragment imports.
          .systemPackages(
              "io.nxmatic.rke2lab.systemd.port;version=1.0.0",
              "io.nxmatic.rke2lab.world.gateway.port;version=1.0.0")
          // The JUnit runner world (launcher + engine + this testkit) — the proxy's own
          // infrastructure, the single shared declaration. What the host+fragment declare they need
          // (doctor.records, which doctor-port imports; doctor.spi, which the fragment's
          // FakeSpecialist imports) is derived from their manifests in the test body via
          // installImportClosureOf — no hand-kept list.
          .withJUnitRunner()
          .build();

  @TestFactory
  Stream<DynamicTest> valueTypeTests() throws Exception {
    // Select the doctor-port fixture by what it DECLARES; its host (doctor-port) is found through
    // the fragment's Fragment-Host — neither named by a literal. Both installed, neither started.
    // Seed the import closure with BOTH host and fragment: the fragment's FakeSpecialist imports
    // doctor.spi, which the host's own manifest does not, so the fragment must be walked too.
    final OutOfContainerFrameworkExtension.FixtureWithHost fixture =
        felix.installFixtureWithHost(FIXTURE_FILTER);
    final Bundle host = fixture.host();
    final List<Bundle> toResolve = new ArrayList<>(List.of(host));
    toResolve.addAll(felix.installImportClosureOf(host, fixture.fragment()));
    if (!felix.resolve(toResolve)) {
      fail("doctor-port host (with its -test fragment) must resolve");
    }
    host.start();

    final Class<?> runner = host.loadClass(RUNNER_FQN);
    final Method run = runner.getMethod("run");
    @SuppressWarnings("unchecked")
    final List<String> results = (List<String>) run.invoke(null);

    if (results.isEmpty()) {
      fail("no in-container test was discovered — the jupiter engine did not attach");
    }

    return results.stream().map(DoctorPortInContainerTest::toDynamicTest);
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
