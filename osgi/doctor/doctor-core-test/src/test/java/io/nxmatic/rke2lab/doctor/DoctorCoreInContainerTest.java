package io.nxmatic.rke2lab.doctor;

import static org.junit.jupiter.api.Assertions.fail;

import io.nxmatic.rke2lab.jgiven.testkit.JGivenTestkit;
import io.nxmatic.rke2lab.junit.testkit.FelixFrameworkExtension;
import io.nxmatic.rke2lab.junit.testkit.Osgi;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.framework.Bundle;

/**
 * The bare-JVM proxy (VSCode-clickable) that runs doctor-core's actor tests IN-CONTAINER. It boots
 * a Felix carrying BOTH worlds — the JUnit bundles (via the runner) and the jGiven boot closure
 * (via {@link JGivenTestkit#felix()}, for the 2 scenario tests) — installs the doctor-core host
 * bundle and this {@code -test} fragment, resolves the host (attaching the fragment, OSGi Core
 * §3.14), then drives a JUnit Platform Launcher FROM INSIDE the framework via {@code
 * DoctorCoreTests}. The actor tests read doctor-core's sealed package-private actors white-box; the
 * jGiven scenarios prove BDD plays in-container for doctor.
 *
 * <p>Each in-container test comes back as an encoded {@link String} mapped to one {@link
 * DynamicTest}, so VSCode shows a node per test and a single failure fails alone.
 */
@Osgi
class DoctorCoreInContainerTest {

  private static final String HOST_ARTIFACT = "doctor-core/target";
  private static final String FRAGMENT_ARTIFACT = "doctor-core-test/target";
  // doctor-port-test contributes the shared fixtures package (doctor.testkit) — installed so the
  // host can wire io.nxmatic.rke2lab.doctor.testkit that doctor-core-test imports.
  private static final String FIXTURES_FRAGMENT_ARTIFACT = "doctor-port-test/target";
  private static final String DOCTOR_PORT_ARTIFACT = "doctor-port/target";
  private static final String RUNNER_FQN = "io.nxmatic.rke2lab.doctor.DoctorCoreTests";

  @RegisterExtension
  static final FelixFrameworkExtension felix =
      JGivenTestkit.felix() // jGiven boot closure (byte-buddy, jgiven-wrap, slf4j/junit packages)
          .installFromClasspath(
              "opentest4j",
              "apiguardian-api",
              "junit-platform-commons",
              "junit-platform-engine",
              "junit-platform-launcher",
              "junit-jupiter-api",
              "junit-jupiter-params",
              "junit-jupiter-engine",
              "junit-testkit/target")
          .build();

  @TestFactory
  Stream<DynamicTest> actorTests() throws Exception {
    // doctor-port carries the value vocabulary + the exported doctor.testkit fixtures (via its own
    // -test fragment); doctor-core depends on it. Install the chain, attach both fragments,
    // resolve.
    Bundle port = felix.install(DOCTOR_PORT_ARTIFACT);
    felix.install(FIXTURES_FRAGMENT_ARTIFACT); // doctor-port-test fragment — exports doctor.testkit
    Bundle host = felix.install(HOST_ARTIFACT);
    felix.install(FRAGMENT_ARTIFACT); // doctor-core-test fragment — never started
    if (!felix.resolve(List.of(port, host))) {
      fail("doctor-port + doctor-core (with their -test fragments) must resolve");
    }
    host.start();

    final Class<?> runner = host.loadClass(RUNNER_FQN);
    final Method run = runner.getMethod("run");
    @SuppressWarnings("unchecked")
    final List<String> results = (List<String>) run.invoke(null);

    if (results.isEmpty()) {
      fail("no in-container test was discovered — the jupiter engine did not attach");
    }

    return results.stream().map(DoctorCoreInContainerTest::toDynamicTest);
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
