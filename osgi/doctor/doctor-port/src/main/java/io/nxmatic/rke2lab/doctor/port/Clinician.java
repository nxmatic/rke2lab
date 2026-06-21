package io.nxmatic.rke2lab.doctor.port;

/**
 * An employed actor in the HealthSystem: a {@link Generalist} or a {@link Specialist}, each
 * carrying a stable {@link ClinicianId} (its identity — who it is, independent of whether it holds
 * a record-reading grant). The id is the join key the grant policy and cohort correlation use.
 */
public interface Clinician {

  ClinicianId clinicianId();
}
