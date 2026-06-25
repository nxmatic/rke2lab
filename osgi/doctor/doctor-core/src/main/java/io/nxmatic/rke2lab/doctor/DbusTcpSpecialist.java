package io.nxmatic.rke2lab.doctor;

import io.nxmatic.rke2lab.doctor.port.Specialist;
import io.nxmatic.rke2lab.doctor.records.*;
import io.nxmatic.rke2lab.doctor.records.Assessment;
import io.nxmatic.rke2lab.doctor.records.Observation;
import io.nxmatic.rke2lab.doctor.records.Prescription;
import io.nxmatic.rke2lab.doctor.records.Referral;
import io.nxmatic.rke2lab.doctor.records.ReferralReply;
import io.nxmatic.rke2lab.doctor.records.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.records.SchemaRef;
import io.nxmatic.rke2lab.doctor.records.Specialty;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.systemd.port.SystemdUnitId;
import java.util.Map;

/**
 * Systemd-domain specialist for the dbus-over-TCP adapter. Reads the observation first (the
 * captured snapshot _is_ the Status/Conditions) and, for a connection-refused symptom, prescribes
 * restarting the adapter unit — the deterministic first treatment for an unreachable dbus-TCP
 * endpoint. The unit name is this specialist's own domain knowledge (it owns the systemd unit it
 * remediates), not a string reached across modules. For any other symptom it declines with a
 * reasoned {@link Assessment} (the "why") and no prescription.
 *
 * <p>Pure: the endpoint and node it names come from the OBSERVATION the producer stamped (flat
 * {@code adapterHost}/{@code adapterPort}/{@code nodeName} details keys), never from {@code
 * BootstrapConfig} — a doctor reads facts off the snapshot, it does not open the door itself. So it
 * lives in {@code doctor-core} beside the other specialists and joins the standard roster.
 */
final class DbusTcpSpecialist implements Specialist {

  /**
   * The adapter unit this specialist remediates — the typed id the manifests producer also names.
   */
  static final String ADAPTER_UNIT = SystemdUnitId.DBUS_TCP_SYSTEM_BUS.serviceUnitName();

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
    final Observation observation = referral.observation();
    final String host = detail(observation, "adapterHost", "unknown");
    final String port = detail(observation, "adapterPort", "unknown");
    final String nodeName = detail(observation, "nodeName", "unknown");
    final String endpoint = host + ":" + port;
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
            Map.of("unit", ADAPTER_UNIT, "host", host),
            restartUnitCommand(nodeName, ADAPTER_UNIT));
    return ReferralReply.prescribing(referral, assessment, prescription);
  }

  /**
   * The human-hint command that restarts a unit on a node via the incus egress route — for
   * CONNECTION_REFUSED the dbus door is down, so the restart cannot go back through dbus; incus
   * exec is the route that survives. One home for the format so it is not reassembled by hand at
   * the call site (or asserted by re-spelling it).
   */
  static String restartUnitCommand(String nodeName, String unit) {
    return "incus exec " + nodeName + " -- systemctl restart " + unit;
  }

  private static String detail(Observation observation, String key, String fallback) {
    final Object value = observation.details().get(key);
    return value == null ? fallback : value.toString();
  }
}
