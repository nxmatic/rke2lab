package io.nxmatic.rke2lab.doctor;

import io.nxmatic.rke2lab.doctor.records.*;
import io.nxmatic.rke2lab.doctor.records.ClinicianId;
import io.nxmatic.rke2lab.doctor.records.Patient;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mints and checks {@link Grant}s. Today grants come from explicit <em>interim</em> rules — a
 * self-grant minted when a patient is admitted, and a cohort grant over the same-backend siblings.
 * Only the <em>source</em> is interim: the {@code (ClinicianId, Patient)} check is genuinely
 * enforced, and a restricted policy excludes any sibling it was not granted. Referral-derivation
 * plugs in here later (deriving cohort grants from referrals recorded in the records) without
 * touching the registry or the clinicians.
 *
 * <p>Immutable: each {@code with*} returns a new policy carrying the added grants.
 */
final class GrantPolicy {

  private final Set<Grant> grants;

  private GrantPolicy(Set<Grant> grants) {
    this.grants = Set.copyOf(grants);
  }

  public static GrantPolicy empty() {
    return new GrantPolicy(Set.of());
  }

  public GrantPolicy withSelfGrant(ClinicianId clinicianId, Patient patient) {
    final Set<Grant> next = new HashSet<>(grants);
    next.add(new Grant(clinicianId, patient));
    return new GrantPolicy(next);
  }

  public GrantPolicy withCohortGrant(ClinicianId clinicianId, List<Patient> cohort) {
    final Set<Grant> next = new HashSet<>(grants);
    for (Patient patient : cohort) {
      next.add(new Grant(clinicianId, patient));
    }
    return new GrantPolicy(next);
  }

  public boolean isGranted(ClinicianId clinicianId, Patient patient) {
    return grants.contains(new Grant(clinicianId, patient));
  }
}
