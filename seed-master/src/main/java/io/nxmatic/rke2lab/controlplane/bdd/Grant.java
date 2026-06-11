package io.nxmatic.rke2lab.controlplane.bdd;

/**
 * The right of a {@link ClinicianId} to read a {@link Patient}'s record. The grant key; there is no
 * separate "MRN" type because {@link Patient} already is the patient identity.
 */
public record Grant(ClinicianId clinicianId, Patient patient) {}
