package io.nxmatic.rke2lab.doctor;

import io.nxmatic.rke2lab.doctor.port.ClinicianId;
import io.nxmatic.rke2lab.doctor.port.Patient;

/**
 * The right of a {@link ClinicianId} to read a {@link Patient}'s record. The grant key; there is no
 * separate "MRN" type because {@link Patient} already is the patient identity.
 */
record Grant(ClinicianId clinicianId, Patient patient) {}
