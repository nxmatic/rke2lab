package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.seed.broker.port.SymptomKind;
import java.util.Map;

/**
 * The canned failing probe behind a fault simulation. When the operator orders an incident ({@code
 * policy.preview.simulate.<scenario>: <failure-kind>}), the checkpoint runs this probe instead of
 * touching live infrastructure: it emits a non-ok view carrying the typed {@link SymptomKind}
 * (under {@code "symptom"}), so a runbook for that incident renders.
 *
 * <p>The symptom is recorded as data, not parsed from prose — the same envelope the doctor reads on
 * the consult. In Increment A nothing routes on it; the FAILED node renders without a prescription.
 */
public final class SimulatedSystemdAdapterProbe {

  private SimulatedSystemdAdapterProbe() {}

  /**
   * A probe that fails with the given symptom, mirroring the real gate's failed observation shape.
   */
  public static SystemdAdapterProbe of(SymptomKind symptom) {
    return config -> observation(config, symptom);
  }

  private static ObservationView observation(BootstrapConfig config, SymptomKind symptom) {
    final String endpoint = config.systemdAdapterDbusHost() + ":" + config.systemdAdapterDbusPort();
    return ObservationView.failed(
        symptom,
        "dbusEndpoint=" + endpoint + " status=failed (simulated incident: " + symptom.slug() + ")",
        Map.of("source", "fault-simulation"));
  }
}
