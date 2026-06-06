package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;
import java.util.Map;
import java.util.Optional;

/**
 * Systemd-domain specialist for the dbus-over-TCP adapter. Reads the dossier first (the captured
 * snapshot _is_ the Status/Conditions) and, for a connection-refused symptom, prescribes restarting
 * the adapter unit — the deterministic first treatment for an unreachable dbus-TCP endpoint. The
 * unit name is this specialist's own domain knowledge (it owns the systemd unit it remediates), not
 * a string reached across modules.
 */
public final class DbusTcpSpecialist implements Specialist {

  /** The adapter unit this specialist remediates (cf. manifests' rke2lab-dbus-tcp-system-bus). */
  static final String ADAPTER_UNIT = "rke2lab-dbus-tcp-system-bus.service";

  private final BootstrapConfig config;

  public DbusTcpSpecialist(BootstrapConfig config) {
    this.config = config;
  }

  @Override
  public SpecialistDomain domain() {
    return SpecialistDomain.SYSTEMD;
  }

  @Override
  public Optional<Prescription> diagnose(Symptom symptom, Dossier dossier) {
    if (symptom != Symptom.CONNECTION_REFUSED) {
      return Optional.empty();
    }
    final String endpoint = config.systemdAdapterDbusHost() + ":" + config.systemdAdapterDbusPort();
    return Optional.of(
        Prescription.of(
            RemediationProgramRef.RESTART_UNIT,
            Map.of("unit", ADAPTER_UNIT, "host", config.systemdAdapterDbusHost()),
            "dbus-TCP endpoint "
                + endpoint
                + " refused the connection — restart the adapter unit on the seed node: "
                + "incus exec "
                + config.nodeName()
                + " -- systemctl restart "
                + ADAPTER_UNIT));
  }
}
