package io.nxmatic.rke2lab.systemd.internal;

import io.nxmatic.rke2lab.doctor.contract.Assessment;
import io.nxmatic.rke2lab.doctor.contract.Observation;
import io.nxmatic.rke2lab.doctor.contract.Prescription;
import io.nxmatic.rke2lab.doctor.contract.Referral;
import io.nxmatic.rke2lab.doctor.contract.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.contract.SchemaRef;
import io.nxmatic.rke2lab.doctor.contract.Specialty;
import io.nxmatic.rke2lab.doctor.contract.Symptom;
import io.nxmatic.rke2lab.doctor.spi.ClinicianProperties;
import io.nxmatic.rke2lab.doctor.spi.Specialist;
import io.nxmatic.rke2lab.systemd.contract.SystemdUnitId;
import java.util.Map;
import java.util.Optional;
import org.osgi.service.component.annotations.Component;

/**
 * The systemd domain's diagnostician for the dbus-over-TCP adapter. Reads the observation first
 * (the captured snapshot _is_ the Status/Conditions) and, for a connection-refused symptom,
 * prescribes restarting the adapter unit — the deterministic first treatment for an unreachable
 * dbus-TCP endpoint. The unit name is this specialist's own domain knowledge (it owns the systemd
 * unit it remediates), not a string reached across modules. For any other symptom it declines with
 * a reasoned {@link Assessment} and no prescription.
 *
 * <p>Contributed to the doctor by Declarative Services: a {@link Specialist} {@code @Component}
 * self-declaring the domain-level diagnostician properties ({@link
 * ClinicianProperties#PROP_DIAGNOSTICIAN} + {@link ClinicianProperties#PROP_TIER_DOMAIN}), which
 * the health system's tier-scoped {@code @Reference} collects. It lives in the systemd domain (its
 * owner — it names the systemd unit and reads systemd-port's typed id), not in doctor-core.
 *
 * <p>Pure: the endpoint and node it names come from the OBSERVATION the producer stamped (flat
 * {@code adapterHost}/{@code adapterPort}/{@code nodeName} details keys), never from config — a
 * doctor reads facts off the snapshot, it does not open the door itself.
 */
@Component(
    service = Specialist.class,
    property = {ClinicianProperties.PROP_DIAGNOSTICIAN, ClinicianProperties.PROP_TIER_DOMAIN})
public final class DbusTcpSpecialist implements Specialist {

  /**
   * The adapter unit this specialist remediates — the typed id the manifests producer also names.
   * Package-private so the colocated unit test pins the contract without re-spelling the unit name.
   */
  static final String ADAPTER_UNIT = SystemdUnitId.DBUS_TCP_SYSTEM_BUS.serviceUnitName();

  @Override
  public Specialty domain() {
    return Specialty.SYSTEMD;
  }

  @Override
  public Assessment assess(Referral referral) {
    final Symptom symptom = referral.symptom();
    if (symptom != Symptom.CONNECTION_REFUSED) {
      return Assessment.of(
          SchemaRef.of("dbus-tcp/declined/v1"),
          Map.of("declinedSymptom", symptom.id()),
          "not a dbus-TCP symptom — the systemd adapter has no treatment for " + symptom.id());
    }
    final Observation observation = referral.observation();
    final String endpoint =
        detail(observation, "adapterHost", "unknown")
            + ":"
            + detail(observation, "adapterPort", "unknown");
    return Assessment.of(
        SchemaRef.of("dbus-tcp/connection-refused/v1"),
        Map.of("endpoint", endpoint, "unit", ADAPTER_UNIT),
        "dbus-TCP endpoint "
            + endpoint
            + " refused the connection — the adapter unit is the deterministic first treatment "
            + "for an unreachable dbus-TCP endpoint.");
  }

  /**
   * Prescribe the adapter-unit restart only for the connection-refused assessment this specialist
   * raises; decline otherwise. The {@code unit} is read back from the assessment (its single
   * source); {@code host}/{@code nodeName} are raw observation facts the assessment does not carry,
   * so they are read off the referral's observation — input facts, not a re-derivation of the
   * assessment.
   */
  @Override
  public Optional<Prescription> prescribe(Referral referral, Assessment assessment) {
    if (referral.symptom() != Symptom.CONNECTION_REFUSED) {
      return Optional.empty();
    }
    final Observation observation = referral.observation();
    final String host = detail(observation, "adapterHost", "unknown");
    final String nodeName = detail(observation, "nodeName", "unknown");
    final String unit = assessment.payload().getOrDefault("unit", ADAPTER_UNIT).toString();
    return Optional.of(
        Prescription.of(
            RemediationProgramRef.RESTART_UNIT,
            Map.of("unit", unit, "host", host),
            restartUnitCommand(nodeName, unit)));
  }

  /**
   * The human-hint command that restarts a unit on a node via the incus egress route — for
   * CONNECTION_REFUSED the dbus door is down, so the restart cannot go back through dbus; incus
   * exec is the route that survives. One home for the format so it is not reassembled by hand.
   */
  static String restartUnitCommand(String nodeName, String unit) {
    return "incus exec " + nodeName + " -- systemctl restart " + unit;
  }

  private static String detail(Observation observation, String key, String fallback) {
    final Object value = observation.details().get(key);
    return value == null ? fallback : value.toString();
  }
}
