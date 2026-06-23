package io.nxmatic.rke2lab.dbus.systemd.edge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.junit.testkit.Osgi;
import io.nxmatic.rke2lab.junit.testkit.OutOfContainerFrameworkExtension;
import io.nxmatic.rke2lab.systemd.port.SystemdProbeRequest;
import io.nxmatic.rke2lab.systemd.port.SystemdRuntimeProbe;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The load-bearing UNKNOWN of this chantier, proven: the dbus-java transport {@code ServiceLoader}
 * resolves {@code TcpTransportProvider} from the edge's OWN {@code Bundle-ClassPath} (the 3 nested
 * jars), inside one bundle classloader — no SPI-Fly, no system leak. The first edge that embeds
 * jars, so this is the proof embedding actually works at runtime.
 *
 * <p>Out-of-container, extension-only (the {@code ScrUnsatisfiedReferenceTest} shape), NOT an
 * in-container fragment: the edge needs no white-box access — {@link SystemdRuntimeProbe} is an
 * EXPORTED systemd-port type, so it is observed TYPED via {@code awaitService}, and calling {@code
 * probe()} runs the dbus-java {@code ServiceLoader} INSIDE the edge's classloader whatever JVM
 * thread calls it. A {@code getRegisteredBusTypes()} probe from here would read the TEST
 * classpath's providers, not the edge's — only the behavioural call exercises the right loader.
 *
 * <p>The proof is the FAILURE MODE, because no real systemd is reachable in a unit test:
 *
 * <ul>
 *   <li>ServiceLoader BROKEN → dbus-java throws {@code TransportRegistrationException("No
 *       dbus-java-transport found in classpath, …")} BEFORE any socket — the edge wraps it.
 *   <li>ServiceLoader OK → the TCP provider is found, dbus-java opens a real socket to a dead port
 *       and fails with a network error (connection refused / ConnectException). Reaching the socket
 *       at all means the transport resolved.
 * </ul>
 *
 * So a connection-level failure is GREEN (transport resolved); a "no transport" failure is the
 * regression this test exists to catch.
 */
@Osgi
class DbusSystemdEdgeBootTest {

  private static final String EDGE_FIXTURE = "(&(type=edge)(edge=dbus-systemd))";
  private static final String NO_TRANSPORT_MARKER = "no dbus-java-transport found";

  // SCR runs so the edge's @Component is published; the seam package is system-exported from ONE
  // place so awaitService(SystemdRuntimeProbe.class) returns the host's own class, castable. The
  // edge is installed by what it DECLARES (its embed capability), never by a symbolic-name literal.
  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      OutOfContainerFrameworkExtension.builder()
          .withScr()
          .systemPackages(
              "io.nxmatic.rke2lab.systemd.port;version=1.0.0", "org.slf4j;version=2.0.0")
          .installMatching(EDGE_FIXTURE)
          .build();

  @Test
  void scrPublishesTheProbeTyped() throws Exception {
    assertNotNull(
        felix.awaitService(SystemdRuntimeProbe.class, 5000),
        "SCR published SystemdRuntimeProbe — the embedded edge @Component activated and the seam"
            + " package resolved single-exporter (typed, no ClassCastException)");
  }

  @Test
  void serviceLoaderFindsTheTcpTransportInTheBundleClasspath() throws Exception {
    final SystemdRuntimeProbe probe = felix.awaitService(SystemdRuntimeProbe.class, 5000);
    assertNotNull(probe, "the probe must be published before we can exercise its transport lookup");

    // Probe a port nothing listens on: open then immediately close a ServerSocket, so the port is
    // free and the OS refuses the connect deterministically — no flakiness, no real systemd needed.
    final int deadPort = closedLocalPort();
    final SystemdProbeRequest request =
        new SystemdProbeRequest("127.0.0.1", deadPort, "boot-test-node", "boot-test-host");

    final IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> probe.probe(request),
            "no systemd is reachable, so the probe must fail — the WHICH failure is the proof");

    final String chain = causeChainLower(failure);
    assertFalse(
        chain.contains(NO_TRANSPORT_MARKER),
        "the dbus-java ServiceLoader did NOT find a transport in the edge's Bundle-ClassPath —"
            + " the embedded nested jars are not on the bundle classpath: "
            + chain);
    assertTrue(
        chain.contains("refused")
            || chain.contains("connectexception")
            || chain.contains("connect")
            || chain.contains("timed out"),
        "expected a connection-level failure (transport resolved, real socket attempted), got: "
            + chain);
  }

  /** A local TCP port that is guaranteed closed: bound to find a free one, then released. */
  private static int closedLocalPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  /**
   * The throwable's whole cause chain, lower-cased, flattened to one string for substring checks.
   */
  private static String causeChainLower(Throwable top) {
    final StringBuilder chain = new StringBuilder();
    for (Throwable t = top; t != null && t != t.getCause(); t = t.getCause()) {
      chain.append(t.getClass().getName()).append(": ").append(t.getMessage()).append(" | ");
    }
    return chain.toString().toLowerCase(Locale.ROOT);
  }
}
