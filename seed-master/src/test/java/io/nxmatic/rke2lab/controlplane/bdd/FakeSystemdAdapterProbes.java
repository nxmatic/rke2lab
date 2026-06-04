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

  /** Endpoint refused: status=failed with the port-12434 connection-refused narrative. */
  static SystemdAdapterProbe connectionRefused() {
    return config ->
        envelope(
            config,
            "failed",
            "Connection refused at "
                + config.systemdAdapterDbusHost()
                + ":"
                + config.systemdAdapterDbusPort());
  }

  private static Map<String, Object> envelope(
      BootstrapConfig config, String status, String detail) {
    final LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", status);
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
