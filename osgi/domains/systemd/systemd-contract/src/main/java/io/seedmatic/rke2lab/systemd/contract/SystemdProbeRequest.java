// @codebase
package io.seedmatic.rke2lab.systemd.contract;

/**
 * The pure input crossing the readiness seam: the few host-side facts {@link SystemdRuntimeProbe}
 * needs to open the dbus-on-TCP endpoint and stamp the connection context. A seam type (loaded by
 * the system bundle), so the flat host fills it from its {@code BootstrapConfig} and the OSGi edge
 * consumes it without the host's config type ever crossing the frontier.
 */
public record SystemdProbeRequest(
    String dbusHost, int dbusPort, String nodeName, String imageBuilderHost) {

  public SystemdProbeRequest {
    dbusHost = normalize(dbusHost, "localhost");
    dbusPort = dbusPort <= 0 ? 0 : dbusPort;
    nodeName = normalize(nodeName, "unknown");
    imageBuilderHost = normalize(imageBuilderHost, "unknown");
  }

  private static String normalize(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }
}
