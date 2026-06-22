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

  // The doctor suite's two fixtures, selected by what they declare. role=core is the host under
  // test (its actors); role=port contributes the shared doctor.testkit fixtures package its sibling
  // imports. Each fragment names its own host (doctor-core / doctor-port) via Fragment-Host, so no
  // host is named by a literal here.
  private static final String CORE_FIXTURE = "(&(type=fixture)(suite=doctor)(role=core))";
  private static final String PORT_FIXTURE = "(&(type=fixture)(suite=doctor)(role=port))";
  private static final String RUNNER_FQN = "io.nxmatic.rke2lab.doctor.DoctorCoreTests";

  @RegisterExtension
  static final FelixFrameworkExtension felix =
      JGivenTestkit.felix() // jGiven boot closure (byte-buddy, jgiven-wrap, slf4j/junit packages)
          .installFromClasspath(
              "org.opentest4j",
              "org.apiguardian.api",
              "junit-platform-commons",
              "junit-platform-engine",
              "junit-platform-launcher",
              "junit-jupiter-api",
              "junit-jupiter-params",
              "junit-jupiter-engine",
              "io.nxmatic.rke2lab.junit.testkit")
          .build();

  @TestFactory
  Stream<DynamicTest> actorTests() throws Exception {
    // doctor-port carries the value vocabulary + the exported doctor.testkit fixtures (via its own
    // -test fragment); doctor-core depends on it. Each fixture installs its host + fragment,
    // located
    // through the fragment's declared Fragment-Host — no host named by a literal. Attach both
    // fragments, resolve both hosts together.
    Bundle port = felix.installFixtureWithHost(PORT_FIXTURE).host();
    Bundle host = felix.installFixtureWithHost(CORE_FIXTURE).host();
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
