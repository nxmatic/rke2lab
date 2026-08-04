package io.nxmatic.rke2lab.incus.core;

import io.nxmatic.rke2lab.junit.testkit.OsgiWorld;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.FrameworkLog;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.InContainerScenarios;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.InContainerScenarios.Provisioning;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OutOfContainerFrameworkExtension;
import io.nxmatic.rke2lab.scenario.testkit.ScenarioTestkit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.framework.Bundle;
import org.osgi.service.log.LogLevel;

/**
 * The bare-JVM proxy (VSCode-clickable) that boots incus-core in-container alongside
 * manifests-core. It boots a Felix carrying the JUnit + jGiven worlds, then installs BOTH domain
 * hosts: incus-core (the fixture's Fragment-Host, driver) AND manifests-core (a second host), plus
 * the full import closure of each, so the two domains resolve and coexist in the SAME registry. The
 * whole graph is STARTED (not merely resolved) so SCR activates the @Components of both. A harness
 * kept for incus-core cross-bundle proofs; the former host-asset materializer proof is dissolved
 * (node-base bakes the node, so there is no /srv/host delivery to prove).
 *
 * <p>This is the reciprocal of {@code IncusBddInContainerTest}, which deliberately excludes
 * manifests-core to keep the incus scion pure; here the two worlds meet on purpose. Only
 * seed.broker.port is system-exported (the host↔OSGi membrane); everything else — both contract
 * faces, the cdk8s carrier + its systemd fragment, unitrepo, jackson, snakeyaml, ipaddress, jgit,
 * java-diff-utils — is de-seamed and derived from the two hosts' manifests via
 * installImportClosureOf.
 *
 * <p>Each in-container test comes back as an encoded {@link String} mapped to one {@link
 * DynamicTest}, so VSCode shows a node per test and a single failure fails alone.
 */
@OsgiWorld
// Flip to LogLevel.DEBUG to troubleshoot a failed in-container resolve/activation (Felix then
// traces
// WHICH requirement could not be wired); WARNING is the quiet committed default.
@FrameworkLog(LogLevel.WARN)
// Disabled: the in-container harness needs at least one test class in the incus-core package, and
// the only one (the host-asset materializer wiring proof) was removed when node-base dissolved the
// /srv/host delivery. Kept — wiring intact — for the next incus-core cross-bundle proof; re-enable
// by adding an in-container test to the package.
@Disabled("no in-container test to drive since the host-asset proof was dissolved")
class IncusCoreInContainerTest {

  // Select the fixture by what it declares (type=fixture, suite, role); its host (incus-core) comes
  // from Fragment-Host. manifests-core is named explicitly below — it is the SECOND host, not a
  // fixture, so it is installed by BSN.
  private static final String CORE_FIXTURE = "(&(type=fixture)(suite=incus)(role=core))";
  private static final String MANIFESTS_CORE_BSN = "io.nxmatic.rke2lab.manifests.core";
  private static final String RUNNER_FQN = "io.nxmatic.rke2lab.incus.core.IncusCoreTests";

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      ScenarioTestkit.felix() // jGiven boot closure (byte-buddy, jgiven-wrap, slf4j/junit packages)
          // manifests-core + incus-core carry @Components, so both bundles Require the DS extender;
          // felix.scr must run for them to resolve and activate.
          .withScr()
          // The ONE package system-exported: seed.broker.port (the host↔OSGi membrane). Everything
          // else is derived from the two hosts' manifests via installImportClosureOf, wired
          // bundle-to-bundle in-container.
          .systemPackages("io.nxmatic.rke2lab.seed.broker.port;version=1.0.0")
          // The JUnit-Platform runner world the front-door drives in-container.
          .withJUnitRunner()
          .build();

  @TestFactory
  Stream<DynamicTest> actorTests() throws Exception {
    return InContainerScenarios.drive(
        felix,
        RUNNER_FQN,
        f -> {
          // The fixture + its Fragment-Host (incus-core), then manifests-core as a second host.
          final Bundle incusHost = f.installFixtureWithHost(CORE_FIXTURE).host();
          final Bundle manifestsHost = f.install(MANIFESTS_CORE_BSN);
          final List<Bundle> toResolve = new ArrayList<>(List.of(incusHost, manifestsHost));
          // Close the import graph over BOTH hosts at once (varargs) — one walk, deduped.
          toResolve.addAll(f.installImportClosureOf(incusHost, manifestsHost));
          // startWholeGraph = true: both hosts ACTIVE so SCR activates their @Components in the one
          // registry (the two domains coexist in-container).
          return new Provisioning(incusHost, toResolve, true);
        });
  }
}
