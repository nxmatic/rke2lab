package io.nxmatic.rke2lab.doctor.contract;

/**
 * A clinician's stable, self-declared id — the join key for grants and cohort correlation. A typed
 * value, not a bare String, per the single-source-of-truth identifier discipline (the {@code
 * clusterApi} bug taught why stringly-typed ids fail silently). Kebab-case by convention, mirroring
 * {@code RemediationProgramRef.id()} / {@code Symptom.id()}.
 */
public record ClinicianId(String value) {

  public ClinicianId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("clinician id cannot be null or blank");
    }
  }
}
