package io.nxmatic.rk2lab.controlplane.systemd;

import io.nxmatic.rk2lab.controlplane.SeedLog;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rk2lab.controlplane.readiness.DbusSystemdProbe;
import io.nxmatic.rk2lab.systemdcontract.api.SystemdStatusSnapshot;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Canonical runtime status snapshot probe backed by systemd-adapter DBus-on-TCP. */
public final class SeedSystemdAdapterRuntimeStatusSnapshot {

  private static final String API_VERSION = "rk2lab.nxmatic.io/v1alpha1";
  private static final String KIND = "SystemdAdapterRuntimeStatus";

  private SeedSystemdAdapterRuntimeStatusSnapshot() {
    // Utility class
  }

  public static Map<String, Object> deferredPreview(BootstrapConfig config) {
    return envelope(
        "deferred-preview",
        "runtime status deferred during preview; probe=systemd-adapter-runtime",
        Map.of("source", "systemd-adapter-runtime-probe", "probeMode", "systemd-adapter-runtime"));
  }

  public static Map<String, Object> snapshot(BootstrapConfig config, Consumer<String> logger) {
    try {
      final SystemdStatusSnapshot statusSnapshot = DbusSystemdProbe.probe(config);
      final LinkedHashMap<String, Object> parsed =
          new LinkedHashMap<>(statusSnapshot.toPayloadMap());
      parsed.put("apiVersion", API_VERSION);
      parsed.put("kind", KIND);
      parsed.put("source", "systemd-adapter-runtime-probe");
      parsed.put("probeMode", "systemd-adapter-runtime");
      parsed.put("status", "ok");
      parsed.putIfAbsent("capturedAt", Instant.now().toString());

      if (logger != null) {
        logger.accept("systemd adapter runtime summary: " + parsed.getOrDefault("summary", "n/a"));
      }
      return Map.copyOf(parsed);
    } catch (IllegalStateException ex) {
      return envelope(
          "execution-error",
          "systemd adapter runtime probe execution error: " + sanitize(ex.getMessage()),
          Map.of(
              "source", "systemd-adapter-runtime-probe", "probeMode", "systemd-adapter-runtime"));
    }
  }

  public static Map<String, Object> snapshotStandalone(BootstrapConfig config) {
    return snapshot(config, message -> SeedLog.info("readiness", message));
  }

  private static String sanitize(String raw) {
    if (raw == null || raw.isBlank()) {
      return "unknown";
    }
    return raw.trim();
  }

  private static Map<String, Object> envelope(
      String status, String summary, Map<String, Object> details) {
    final LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
    payload.put("apiVersion", API_VERSION);
    payload.put("kind", KIND);
    payload.put("status", status);
    payload.put("summary", summary);
    payload.put("capturedAt", Instant.now().toString());
    if (details != null && !details.isEmpty()) {
      payload.putAll(details);
    }
    return Map.copyOf(payload);
  }
}
