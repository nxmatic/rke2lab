package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;
import java.util.Map;

/**
 * The canned failing probe behind a fault simulation. When the operator orders an incident ({@code
 * policy.preview.simulate.<scenario>: <failure-kind>}), the checkpoint runs this probe instead of
 * touching live infrastructure: it emits a non-ok envelope carrying the typed {@link Symptom}
 * (under {@link Symptom#ENVELOPE_KEY}), so a runbook for that incident renders.
 *
 * <p>The symptom is recorded as data, not parsed from prose — the same envelope the doctor will
 * read in Increment B. In Increment A nothing routes on it; the FAILED node renders without a
 * prescription.
 */
public final class SimulatedSystemdAdapterProbe {

  private SimulatedSystemdAdapterProbe() {}

  /** A probe that fails with the given symptom, mirroring the real gate's failed dossier shape. */
  public static SystemdAdapterProbe of(Symptom symptom) {
    return config -> dossier(config, symptom);
  }

  private static Dossier dossier(BootstrapConfig config, Symptom symptom) {
    final String endpoint = config.systemdAdapterDbusHost() + ":" + config.systemdAdapterDbusPort();
    return Dossier.failed(
        symptom,
        "dbusEndpoint=" + endpoint + " status=failed (simulated incident: " + symptom.id() + ")",
        Map.of("source", "fault-simulation"));
  }
}
