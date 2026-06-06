package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;
import java.util.LinkedHashMap;
import java.util.Map;

/** Canned probe outcomes used by the DSL-first scenario. */
final class FakeSystemdAdapterProbes {

  private FakeSystemdAdapterProbes() {}

  /** Endpoint reachable: status=ok, mirroring the real gate's success envelope. */
  static SystemdAdapterProbe reachable() {
    return config -> envelope(config, "ok", "dbusEndpoint reachable");
  }

  /** Endpoint refused: status=failed, carrying the typed symptom the doctor will read. */
  static SystemdAdapterProbe connectionRefused() {
    return config ->
        envelope(
            config,
            "failed",
            Symptom.CONNECTION_REFUSED,
            "Connection refused at "
                + config.systemdAdapterDbusHost()
                + ":"
                + config.systemdAdapterDbusPort());
  }

  private static Map<String, Object> envelope(
      BootstrapConfig config, String status, String detail) {
    return envelope(config, status, null, detail);
  }

  private static Map<String, Object> envelope(
      BootstrapConfig config, String status, Symptom symptom, String detail) {
    final LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", status);
    if (symptom != null) {
      payload.put(Symptom.ENVELOPE_KEY, symptom.id());
    }
    payload.put(
        "summary",
        "dbusEndpoint="
            + config.systemdAdapterDbusHost()
            + ":"
            + config.systemdAdapterDbusPort()
            + " status="
            + status
            + " ("
            + detail
            + ")");
    return Map.copyOf(payload);
  }
}
