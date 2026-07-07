package io.nxmatic.rke2lab.controlplane.systemd;

import io.nxmatic.rke2lab.config.port.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.SeedLog;
import io.nxmatic.rke2lab.systemd.port.SystemdProbeRequest;
import io.nxmatic.rke2lab.systemd.port.SystemdRuntimeProbe;
import io.nxmatic.rke2lab.systemd.port.SystemdStatusSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Canonical runtime status snapshot probe backed by the systemd-adapter dbus-on-TCP edge. An
 * instance, not a static helper: it holds the {@link SystemdRuntimeProbe} resolved once from the
 * booted OSGi registry (the dbus-systemd-edge {@code @Component}), so every readiness site that
 * needs a status snapshot is passed this instance rather than reaching the edge statically.
 */
public final class SeedSystemdAdapterRuntimeStatusSnapshot {

  private static final String API_VERSION = "rke2lab.nxmatic.io/v1alpha1";
  private static final String KIND = "SystemdAdapterRuntimeStatus";

  private static final Consumer<String> NOOP_LOGGER = message -> {};

  private final SystemdRuntimeProbe probe;

  public SeedSystemdAdapterRuntimeStatusSnapshot(SystemdRuntimeProbe probe) {
    this.probe = probe;
  }

  /** Snapshot with no progress logging (the gate's polling path, where the line would be noise). */
  public Map<String, Object> snapshot(BootstrapConfig config) {
    return snapshot(config, NOOP_LOGGER);
  }

  public static Map<String, Object> deferredPreview(BootstrapConfig config) {
    return envelope(
        "deferred-preview",
        "runtime status deferred during preview; probe=systemd-adapter-runtime",
        Map.of("source", "systemd-adapter-runtime-probe", "probeMode", "systemd-adapter-runtime"));
  }

  public Map<String, Object> snapshot(BootstrapConfig config, Consumer<String> logger) {
    try {
      final SystemdStatusSnapshot statusSnapshot = probe.probe(requestFrom(config));
      final LinkedHashMap<String, Object> parsed =
          new LinkedHashMap<>(statusSnapshot.toPayloadMap());
      parsed.put("apiVersion", API_VERSION);
      parsed.put("kind", KIND);
      parsed.put("source", "systemd-adapter-runtime-probe");
      parsed.put("probeMode", "systemd-adapter-runtime");
      parsed.put("status", "ok");

      if (logger != null) {
        logger.accept("systemd adapter runtime summary: " + parsed.getOrDefault("summary", "n/a"));
      }
      return Map.copyOf(parsed);
    } catch (IllegalStateException ex) {
      final String detail =
          Optional.ofNullable(ex.getMessage())
              .map(String::trim)
              .filter(s -> !s.isBlank())
              .orElse("unknown");
      return envelope(
          "execution-error",
          "systemd adapter runtime probe execution error: " + detail,
          Map.of(
              "source", "systemd-adapter-runtime-probe", "probeMode", "systemd-adapter-runtime"));
    }
  }

  public Map<String, Object> snapshotStandalone(BootstrapConfig config) {
    return snapshot(config, message -> SeedLog.info("readiness", message));
  }

  private static SystemdProbeRequest requestFrom(BootstrapConfig config) {
    return new SystemdProbeRequest(
        config.systemdAdapterDbusHost(),
        config.systemdAdapterDbusPort(),
        config.nodeName(),
        config.imageBuilderHost());
  }

  private static Map<String, Object> envelope(
      String status, String summary, Map<String, Object> details) {
    final LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
    payload.put("apiVersion", API_VERSION);
    payload.put("kind", KIND);
    payload.put("status", status);
    payload.put("summary", summary);
    if (details != null && !details.isEmpty()) {
      payload.putAll(details);
    }
    return Map.copyOf(payload);
  }
}
