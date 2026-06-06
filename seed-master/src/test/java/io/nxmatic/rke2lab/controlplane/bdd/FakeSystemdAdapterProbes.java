package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;
import java.util.Map;
import java.util.Optional;

/** Canned probe outcomes used by the DSL-first scenario. */
final class FakeSystemdAdapterProbes {

  private FakeSystemdAdapterProbes() {}

  /** Endpoint reachable: status=ok, mirroring the real gate's success dossier. */
  static SystemdAdapterProbe reachable() {
    return config -> dossier(config, "ok", Optional.empty(), "dbusEndpoint reachable");
  }

  /** Endpoint refused: status=failed, carrying the typed symptom the doctor will read. */
  static SystemdAdapterProbe connectionRefused() {
    return config ->
        dossier(
            config,
            "failed",
            Optional.of(Symptom.CONNECTION_REFUSED),
            "Connection refused at "
                + config.systemdAdapterDbusHost()
                + ":"
                + config.systemdAdapterDbusPort());
  }

  private static Dossier dossier(
      BootstrapConfig config, String status, Optional<Symptom> symptom, String detail) {
    final String summary =
        "dbusEndpoint="
            + config.systemdAdapterDbusHost()
            + ":"
            + config.systemdAdapterDbusPort()
            + " status="
            + status
            + " ("
            + detail
            + ")";
    return Dossier.of(status, symptom, summary, Map.of());
  }
}
