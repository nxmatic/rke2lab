package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.junit.testkit.OsgiWorld;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OutOfContainerFrameworkExtension;
import io.nxmatic.rke2lab.systemd.port.SystemdRuntimeProbe;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.framework.Bundle;

/**
 * Proves the version-selection seam end-to-end in a REAL Felix: the fake @Components contributed by
 * the {@code dbus-systemd-edge-fake} and {@code doctor-core-fake} fragments (both {@code
 * variant=fake}) are wired by SCR into the live registry alongside the live impls, and a connection
 * carrying the {@code (variant=fake)} selector resolves the FAKES — while a plain connection (no
 * selector) never resolves a fake by default (the negative service.ranking guard).
 *
 * <p>This is the in-container half of island 1: {@code PureStagesTest} proves the offline play,
 * {@code SystemdAdapterVerdictTest} the verdict decision; this proves the OSGi wiring the stage's
 * {@code awaitService} rides. The fakes attach to their hosts (the edge / doctor-core), so SCR
 * scans the host fragments (DS 112.4.1) and activates them — the fragment-contribution model.
 */
@OsgiWorld
class SystemdAdapterStageTest {

  private static final String DBUS_FAKE = "(&(type=fixture)(suite=systemd)(role=probe-fake))";
  private static final String DOCTOR_FAKE = "(&(type=fixture)(suite=doctor)(role=gateway-fake))";

  // The seam packages are system-exported from ONE place so awaitService returns the host's own
  // class, castable across the boundary — the single-exporter trick BootPlanner applies in prod.
  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      OutOfContainerFrameworkExtension.builder()
          .withScr()
          .systemPackages(
              "io.nxmatic.rke2lab.systemd.port;version=1.0.0",
              "io.nxmatic.rke2lab.seed.broker.port;version=1.0.0",
              "io.nxmatic.rke2lab.doctor.port;version=1.0.0",
              "org.slf4j;version=2.0.0")
          .build();

  @BeforeAll
  static void installFakesWithHosts() throws Exception {
    final List<Bundle> hosts = new ArrayList<>();
    hosts.add(felix.installFixtureWithHost(DBUS_FAKE).host());
    hosts.add(felix.installFixtureWithHost(DOCTOR_FAKE).host());
    // Pull each host's import closure (its sibling bundles + third-party libs), then resolve the
    // whole set so the fragments attach (OSGi Core §3.14) and start the hosts so SCR activates.
    final List<Bundle> toResolve = new ArrayList<>(hosts);
    for (Bundle host : hosts) {
      toResolve.addAll(felix.installImportClosureOf(host));
    }
    assertTrue(felix.resolve(toResolve), "the fake-fragment hosts (and closure) must resolve");
    for (Bundle host : hosts) {
      host.start();
    }
  }

  @Test
  void theSelectorResolvesTheFakeSystemdRuntimeProbe() throws Exception {
    final OsgiConnection selecting =
        OsgiConnection.over(felix.context(), false, () -> {}, Optional.of("(variant=fake)"));
    assertNotNull(
        selecting.awaitService(SystemdRuntimeProbe.class, 5000),
        "the (variant=fake) selector resolves the fragment-contributed fake SystemdRuntimeProbe");
  }

  @Test
  void aFakeCarriesNegativeRankingSoItNeverWinsAPlainLookup() throws Exception {
    // The default-safety guard, read from the registry itself: the fake's ServiceReference carries
    // service.ranking = -1000, so a selector-less awaitService(Class) prefers ANY live impl and can
    // never pick a fake by default (getServiceReference returns the highest ranking). Asserted on
    // the
    // reference (robust) rather than by needing a live impl staged alongside to lose to.
    final var refs =
        felix.context().getServiceReferences(SystemdRuntimeProbe.class, "(variant=fake)");
    assertTrue(refs.iterator().hasNext(), "the fake SystemdRuntimeProbe is in the registry");
    final Integer ranking = (Integer) refs.iterator().next().getProperty("service.ranking");
    assertEquals(
        Integer.valueOf(-1000),
        ranking,
        "the fake declares a negative ranking — it never wins by default");
  }
}
