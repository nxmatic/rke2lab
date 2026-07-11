package io.nxmatic.rke2lab.dbus.systemd.edge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.systemd.contract.SystemdProbeRequest;
import io.nxmatic.rke2lab.systemd.contract.SystemdRuntimeProbe;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

/**
 * The load-bearing UNKNOWN of this chantier, proven IN-CONTAINER: the dbus-java transport {@code
 * ServiceLoader} resolves {@code TcpTransportProvider} from the edge's OWN {@code Bundle-ClassPath}
 * (the 3 nested jars), inside one bundle classloader — no SPI-Fly, no system leak. Run WHERE the
 * edge lives (this passenger shares the dbus-systemd-edge host loader through the fragment): it
 * resolves the SCR-published {@link SystemdRuntimeProbe} from the edge's OWN registry and calls
 * {@code probe()} TYPED — so {@code systemd.contract} need NOT be system-exported (which would
 * legitimize the host reading a de-seamed contract typed in the live boot), AND calling {@code
 * probe()} here runs the dbus-java ServiceLoader inside the edge's classloader, exactly what must
 * be proven.
 *
 * <p>The proof is the FAILURE MODE, because no real systemd is reachable in a unit test:
 *
 * <ul>
 *   <li>ServiceLoader BROKEN → dbus-java throws {@code TransportRegistrationException("No
 *       dbus-java-transport found in classpath, …")} BEFORE any socket — the edge wraps it.
 *   <li>ServiceLoader OK → the TCP provider is found, dbus-java opens a real socket to a dead port
 *       and fails with a network error. Reaching the socket at all means the transport resolved.
 * </ul>
 *
 * So a connection-level failure is GREEN (transport resolved); a "no transport" failure is the
 * regression this test exists to catch.
 */
public class DbusSystemdProbeInContainerTest {

  private static final String NO_TRANSPORT_MARKER = "no dbus-java-transport found";

  @Test
  void scr_publishes_the_probe() {
    assertNotNull(
        resolveProbe(),
        "SCR published SystemdRuntimeProbe — the embedded edge @Component activated in-container");
  }

  @Test
  void service_loader_finds_the_tcp_transport_in_the_bundle_classpath() throws Exception {
    final SystemdRuntimeProbe probe = resolveProbe();
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

  /**
   * Resolve the edge's SCR-published probe from THIS bundle's own registry (same realm), bounded —
   * SCR activation is asynchronous after the edge bundle starts. Uses only {@code
   * org.osgi.framework}, resolvable in-container.
   */
  private static SystemdRuntimeProbe resolveProbe() {
    final BundleContext context =
        FrameworkUtil.getBundle(DbusSystemdEdgeTests.class).getBundleContext();
    final long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      final ServiceReference<SystemdRuntimeProbe> ref =
          context.getServiceReference(SystemdRuntimeProbe.class);
      if (ref != null) {
        final SystemdRuntimeProbe probe = context.getService(ref);
        if (probe != null) {
          return probe;
        }
      }
      try {
        Thread.sleep(25);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    }
    return null;
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
