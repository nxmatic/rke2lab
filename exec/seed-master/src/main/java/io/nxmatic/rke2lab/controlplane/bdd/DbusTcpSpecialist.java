package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.doctor.port.Assessment;
import io.nxmatic.rke2lab.doctor.port.Prescription;
import io.nxmatic.rke2lab.doctor.port.Referral;
import io.nxmatic.rke2lab.doctor.port.ReferralReply;
import io.nxmatic.rke2lab.doctor.port.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.port.SchemaRef;
import io.nxmatic.rke2lab.doctor.port.Specialist;
import io.nxmatic.rke2lab.doctor.port.Specialty;
import io.nxmatic.rke2lab.doctor.port.Symptom;
import java.util.Map;

/**
 * Systemd-domain specialist for the dbus-over-TCP adapter. Reads the observation first (the
 * captured snapshot _is_ the Status/Conditions) and, for a connection-refused symptom, prescribes
 * restarting the adapter unit — the deterministic first treatment for an unreachable dbus-TCP
 * endpoint. The unit name is this specialist's own domain knowledge (it owns the systemd unit it
 * remediates), not a string reached across modules. For any other symptom it declines with a
 * reasoned {@link Assessment} (the "why") and no prescription.
 */
public final class DbusTcpSpecialist implements Specialist {

  /** The adapter unit this specialist remediates (cf. manifests' rke2lab-dbus-tcp-system-bus). */
  static final String ADAPTER_UNIT = "rke2lab-dbus-tcp-system-bus.service";

  private final BootstrapConfig config;

  public DbusTcpSpecialist(BootstrapConfig config) {
    this.config = config;
  }

  @Override
  public Specialty domain() {
    return Specialty.SYSTEMD;
  }

  @Override
  public ReferralReply diagnose(Referral referral) {
    final Symptom symptom = referral.symptom();
    if (symptom != Symptom.CONNECTION_REFUSED) {
      final Assessment assessment =
          Assessment.of(
              SchemaRef.of("dbus-tcp/declined/v1"),
              Map.of("declinedSymptom", symptom.id()),
              "not a dbus-TCP symptom — the systemd adapter has no treatment for " + symptom.id());
      return ReferralReply.assessing(referral, assessment);
    }
    final String endpoint = config.systemdAdapterDbusHost() + ":" + config.systemdAdapterDbusPort();
    final Assessment assessment =
        Assessment.of(
            SchemaRef.of("dbus-tcp/connection-refused/v1"),
            Map.of("endpoint", endpoint, "unit", ADAPTER_UNIT),
            "dbus-TCP endpoint "
                + endpoint
                + " refused the connection — the adapter unit is the deterministic first treatment "
                + "for an unreachable dbus-TCP endpoint.");
    final Prescription prescription =
        Prescription.of(
            RemediationProgramRef.RESTART_UNIT,
            Map.of("unit", ADAPTER_UNIT, "host", config.systemdAdapterDbusHost()),
            "incus exec " + config.nodeName() + " -- systemctl restart " + ADAPTER_UNIT);
    return ReferralReply.prescribing(referral, assessment, prescription);
  }
}
